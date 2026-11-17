package com.csc.translate.pipeline

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

private const val MIN_SPEECH = (0.25 * RATE).toInt()
private const val PREROLL = (0.4 * RATE).toInt()
/** Matches qwen-asr-demo-streaming's CHUNK_MS = 500. */
private const val VOLATILE = (0.5 * RATE).toInt()
private const val GAP = RATE
/** After a completed sentence, a breath shorter than this stays in the same stream. */
private const val ENDED_GAP = (0.35 * RATE).toInt()
private const val LONGEST = 15 * RATE
private const val TAIL_WAIT = (0.6 * RATE).toInt()
private const val DRAFT_GAP_MS = 600L
private const val TRANSLATION_QUEUE = 64
private const val ASR_CONTEXT_LIMIT = 80
private const val ASR_FAILS = 5

/**
 * The phone's ASR + translate path. It hears 16 kHz PCM, cuts speech the way Tomoru's Qwen3-ASR
 * stretch runner does, and pushes only the samples not yet sent to [decode]. A true second
 * argument finishes that utterance. [translate] runs for each locked line. Each partial is shown
 * in full; the last few pieces stay unfixed. A sentence stays once shown. A new stream may carry
 * the recent caption as context. A sentence with speech after it still waits 0.35 s of quiet
 * before the stream ends, so a breath does not split one utterance. The unfinished tail of a
 * final waits 0.6 s, and joins the next stretch if the speaker goes on.
 *
 * Finished lines wait in a queue of [translationQueue] and are translated one at a time, as
 * Tomoru's live session does. A line that does not fit is left untranslated. The draft of a line
 * stays on screen until its translation returns, and the draft request in flight is cancelled
 * when the line is committed.
 */
internal class StretchSession(
    parent: CoroutineScope,
    private val srcLang: String,
    private val tgtLang: String,
    private val decode: suspend (pcm: ByteArray, finish: Boolean, context: String) -> String,
    private val translate: suspend (text: String, draft: Boolean) -> String,
    private val release: () -> Unit = {},
    floor: Double = 0.02,
    private val voice: Voice = Level(floor),
    private val onView: (src: String, tgt: String) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
    private val translationQueue: Int = TRANSLATION_QUEUE,
) {
    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
    private val detector = Detector(voice)
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    private val translations = Channel<Work>(translationQueue)
    private val lock = Any()
    private val inflight = AtomicInteger(0)
    private val finished = CompletableDeferred<Unit>()

    private val pcm = ByteArrayOutputStream()
    private var base = 0
    private var total = 0
    private var stepped = 0
    private var voiceOn = false
    private var heardVoice = false
    private var onAt = 0
    private var offAt = 0
    private var open = false
    private var ended = false
    private var start = 0
    private var sent = 0
    private var decodedAt = 0
    private var generation = 0
    private var pendingDecode: Pending? = null
    private var asrFails = 0

    private val hypothesis = Hypothesis()
    private val lines = mutableListOf<Line>()
    private var pending = ""
    private var draft = ""
    private var draftBusy = false
    private var draftJob: Job? = null
    private var draftLen = 0
    private var draftAt = 0L
    private var draftGen = 0
    private var shown = ""
    private var discarded = 0

    private val job = scope.launch {
        try {
            run()
        } catch (t: Throwable) {
            if (t !is CancellationException) onError(t)
        } finally {
            runCatching { release() }
            finished.complete(Unit)
        }
    }

    init {
        scope.launch {
            for (work in translations) translateFinal(work)
        }
    }

    fun write(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        incoming.trySend(Incoming.Audio(chunk))
    }

    suspend fun finish() {
        incoming.send(Incoming.Finish)
        finished.await()
    }

    fun stop() {
        scope.cancel()
        (voice as? AutoCloseable)?.close()
    }

    fun snapshot(): Pair<String, String> = synchronized(lock) { source() to target() }

    /** For tests: returns once audio queued before the call has been decoded. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        incoming.send(Incoming.Sync(done))
        done.await()
    }

    private suspend fun run() {
        while (true) {
            val first = incoming.receive()
            val batch = mutableListOf(first)
            while (true) {
                val next = incoming.tryReceive().getOrNull() ?: break
                batch += next
            }
            var stop = false
            var sync: CompletableDeferred<Unit>? = null
            for (item in batch) {
                when (item) {
                    is Incoming.Audio -> feed(item.pcm)
                    Incoming.Finish -> stop = true
                    is Incoming.Sync -> sync = item.done
                }
            }
            val req = takePending()
            if (req != null) applyDecoded(req)
            val tail = takeQuiet()
            if (tail.isNotEmpty()) publish(tail)
            if (stop) {
                val partial = takePending()
                if (partial != null) applyDecoded(partial)
                val last = finalNow()
                if (last != null) applyDecoded(last)
                val flushed = lockTail()
                if (flushed.isNotEmpty()) publish(flushed)
                sync?.complete(Unit)
                val deadline = System.nanoTime() + 30_000_000_000L
                while (inflight.get() > 0 && System.nanoTime() < deadline) delay(10)
                return
            }
            sync?.complete(Unit)
        }
    }

    private suspend fun callDecode(pcm: ByteArray, finish: Boolean, context: String): String =
        withContext(Dispatchers.IO) { decode(pcm, finish, context) }

    private fun asrContext(): String = synchronized(lock) {
        source().replace("\n", "").takeLast(ASR_CONTEXT_LIMIT)
    }

    private suspend fun applyDecoded(req: Pending) {
        val text = try {
            decodeLogged(req)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            asrFails++
            trace("asr fail $asrFails ${t.message.orEmpty().take(80)}")
            if (asrFails >= ASR_FAILS) throw t
            hypothesis.salvageUnfixed()
            return
        }
        asrFails = 0
        apply(req, text)
    }

    private suspend fun decodeLogged(req: Pending): String {
        val seconds = req.pcm.size / 2f / RATE
        val started = System.nanoTime()
        val text = callDecode(req.pcm, req.final, asrContext())
        val ms = (System.nanoTime() - started) / 1_000_000
        trace(
            "asr ${if (req.final) "final" else "partial"} ${"%.1f".format(seconds)}s ${ms}ms ${text.take(120)}",
        )
        return text
    }

    private fun feed(chunk: ByteArray) {
        pcm.write(chunk)
        total += chunk.size / 2
        for (change in detector.push(pcmToFloat(chunk))) {
            val at = (change.tSec * RATE).toInt()
            heardVoice = true
            if (change.on && !voiceOn) {
                voiceOn = true
                onAt = at
            } else if (!change.on) {
                voiceOn = false
                offAt = at
            }
        }
        while (total - stepped >= FRAME) {
            stepped += FRAME
            onStep()
        }
    }

    private fun speaking(): Boolean {
        if (!heardVoice) return false
        if (voiceOn) return open || stepped >= onAt + MIN_SPEECH
        val gap = when {
            open && ended -> ENDED_GAP
            open && stepped - start >= LONGEST -> 0
            else -> GAP
        }
        return open && stepped < offAt + gap
    }

    private fun onStep() {
        val speaking = speaking()
        when {
            speaking && !open -> {
                if (discarded >= RATE) trace("idle discarded ${"%.1f".format(discarded / RATE.toFloat())}s")
                discarded = 0
                open = true
                ended = false
                start = max(base, onAt - PREROLL)
                if (sent < start) sent = start
                decodedAt = stepped
                generation++
                hypothesis.resetStretch()
                trace("stretch open")
            }
            speaking && open && stepped - start >= LONGEST * 2 -> {
                trace("stretch cut ${"%.1f".format((stepped - start) / RATE.toFloat())}s")
                queue(final = true, end = stepped)
                start = stepped
                if (sent < stepped) sent = stepped
                decodedAt = stepped
                ended = false
                open = true
                generation++
                hypothesis.resetStretch()
            }
            speaking && open && stepped - decodedAt >= VOLATILE -> {
                decodedAt = stepped
                queue(final = false, end = stepped)
            }
            !speaking && open -> {
                val why = when {
                    ended -> "sentence"
                    stepped - start >= LONGEST -> "after15"
                    else -> "silence"
                }
                trace(
                    "stretch end $why heard ${"%.1f".format((stepped - start) / RATE.toFloat())}s " +
                        "quiet ${"%.1f".format((stepped - offAt) / RATE.toFloat())}s",
                )
                queue(final = true, end = stepped)
            }
        }
        if (!open) {
            val keep = PREROLL + MIN_SPEECH + FRAME
            if (stepped - base > keep * 2) cut(stepped - keep)
        }
    }

    private fun queue(final: Boolean, end: Int) {
        val audio = slice(sent, end)
        if (audio.isEmpty() && !final) return
        val req = Pending(audio, final, generation, hypothesis.alignPrefix, end)
        val current = pendingDecode
        if (current == null || final || !current.final) pendingDecode = req
        if (final) open = false
    }

    private fun takePending(): Pending? {
        val req = pendingDecode ?: return null
        pendingDecode = null
        if (req.end > sent) sent = req.end
        return req
    }

    private fun finalNow(): Pending? {
        if (!open) return null
        val audio = slice(sent, total)
        open = false
        if (total > sent) sent = total
        return Pending(audio, final = true, generation, hypothesis.alignPrefix, total)
    }

    private fun takeQuiet(): List<String> {
        if (open || voiceOn || !heardVoice || stepped < offAt + TAIL_WAIT) return emptyList()
        if (!hypothesis.hasTail()) return emptyList()
        return lockTail()
    }

    private fun lockTail(): List<String> {
        val locked = hypothesis.commitHeld()
        if (locked.isNotEmpty()) trace("tail ${locked.joinToString("|").take(80)}")
        return locked
    }

    private fun apply(req: Pending, text: String) {
        if (req.generation != generation && !req.final) {
            trace("drop partial")
            return
        }
        val newly = if (req.final) {
            val quiet = heardVoice && !voiceOn && stepped >= offAt + TAIL_WAIT
            hypothesis.absorb(text, req.alignPrefix, final = true, quiet = quiet)
        } else {
            val shown = stripVolatileEnd(hypothesis.settle(text))
            if (hypothesis.provisional) {
                hypothesis.preview(shown)
                emptyList()
            } else {
                if (splitHypothesis(hypothesis.stable).first.isNotEmpty()) ended = true
                val newly = hypothesis.absorb(
                    stripVolatileEnd(hypothesis.stable),
                    hypothesis.alignPrefix,
                    final = false,
                    quiet = false,
                )
                hypothesis.preview(shown)
                newly
            }
        }
        if (newly.isNotEmpty()) trace("lock ${newly.joinToString("|").take(80)}")
        publish(newly)
    }

    private fun publish(newly: List<String>) {
        var draftWork: Work? = null
        val view: Pair<String, String>
        synchronized(lock) {
            for (sentence in newly) {
                val kept = draft
                draft = ""
                draftLen = 0
                draftGen++
                cancelDraft()
                val line = Line(sentence)
                line.draft = kept
                lines += line
                val work = Work(lines.lastIndex, sentence, draft = false, gen = draftGen)
                inflight.incrementAndGet()
                if (translations.trySend(work).isFailure) {
                    inflight.decrementAndGet()
                    line.skipped = true
                    line.draft = ""
                }
            }
            pending = hypothesis.pending
            if (hypothesis.pending.isEmpty() && newly.isNotEmpty()) {
                draft = ""
                draftLen = 0
            }
            draftWork = maybeDraft()
            view = source() to target()
        }
        val (src, tgt) = view
        if (shown.isNotEmpty() && src.length + 12 < shown.length) {
            trace("source shrank ${shown.length} -> ${src.length} ${src.takeLast(80)}")
        }
        shown = src
        onView(src, tgt)
        draftWork?.let { launchDraft(it) }
    }

    private fun maybeDraft(): Work? {
        val n = lettersOf(pending)
        if (n == 0) {
            draft = ""
            draftLen = 0
            return null
        }
        if (draftBusy || n - draftLen < draftStep(srcLang) || System.currentTimeMillis() - draftAt < DRAFT_GAP_MS) {
            return null
        }
        var text = pending
        if (spaced(srcLang)) {
            val cut = text.lastIndexOf(' ')
            if (cut > 0 && !text.endsWith(" ")) text = text.substring(0, cut)
        }
        text = text.trim()
        if (text.isEmpty()) return null
        draftBusy = true
        draftLen = n
        draftAt = System.currentTimeMillis()
        return Work(index = -1, text = text, draft = true, gen = draftGen)
    }

    /** Drops the draft request. The caller holds [lock]. */
    private fun cancelDraft() {
        draftJob?.cancel()
        draftJob = null
        draftBusy = false
    }

    private fun launchDraft(work: Work) {
        inflight.incrementAndGet()
        val ran = AtomicBoolean(false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            ran.set(true)
            try {
                val out = translate(work.text, true)
                var next: Work? = null
                var view: Pair<String, String>? = null
                synchronized(lock) {
                    if (work.gen == draftGen && out.isNotBlank()) draft = out
                    if (draftJob === coroutineContext[Job]) {
                        draftBusy = false
                        draftJob = null
                        next = maybeDraft()
                        view = source() to target()
                    }
                }
                view?.let { (src, tgt) -> onView(src, tgt) }
                if (next != null) launchDraft(next)
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (draftJob === coroutineContext[Job]) {
                        draftBusy = false
                        draftJob = null
                    }
                }
            } finally {
                inflight.decrementAndGet()
            }
        }
        // A lazy job cancelled before it starts never runs its finally.
        job.invokeOnCompletion { if (!ran.get()) inflight.decrementAndGet() }
        synchronized(lock) {
            if (work.gen != draftGen) {
                job.cancel()
                return
            }
            draftJob = job
        }
        job.start()
    }

    private suspend fun translateFinal(work: Work) {
        try {
            val out = translate(work.text, false)
            var view: Pair<String, String>? = null
            synchronized(lock) {
                val line = lines.getOrNull(work.index)
                if (line != null && line.src == work.text && out.isNotBlank()) {
                    line.tgt = out
                    view = source() to target()
                }
            }
            view?.let { (src, tgt) -> onView(src, tgt) }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            var view: Pair<String, String>? = null
            synchronized(lock) {
                val line = lines.getOrNull(work.index)
                if (line != null && line.tgt.isBlank()) {
                    line.draft = ""
                    view = source() to target()
                }
            }
            view?.let { (src, tgt) -> onView(src, tgt) }
        } finally {
            inflight.decrementAndGet()
        }
    }

    private fun source(): String = buildString {
        for (line in lines) {
            if (line.src.isBlank()) continue
            if (isNotEmpty()) append('\n')
            append(line.src)
        }
        if (pending.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(pending)
        }
    }

    private fun target(): String = buildString {
        for (line in lines) {
            val text = when {
                line.tgt.isNotBlank() -> line.tgt
                line.skipped -> ""
                else -> line.draft
            }
            if (text.isBlank()) continue
            if (isNotEmpty()) append('\n')
            append(text)
        }
        if (draft.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(draft)
        }
    }

    private fun slice(from: Int, to: Int): ByteArray {
        val startByte = (from - base) * 2
        val endByte = (to - base) * 2
        val bytes = pcm.toByteArray()
        if (startByte < 0 || endByte > bytes.size || endByte <= startByte) return ByteArray(0)
        return bytes.copyOfRange(startByte, endByte)
    }

    private fun cut(to: Int) {
        val drop = (to - base) * 2
        val bytes = pcm.toByteArray()
        if (drop <= 0 || drop > bytes.size) return
        if (!open) discarded += drop / 2
        pcm.reset()
        pcm.write(bytes, drop, bytes.size - drop)
        base = to
    }
}

private class Line(
    val src: String,
    var tgt: String = "",
    var draft: String = "",
    var skipped: Boolean = false,
)

private data class Pending(
    val pcm: ByteArray,
    val final: Boolean,
    val generation: Int,
    val alignPrefix: String,
    val end: Int,
)

private data class Work(val index: Int, val text: String, val draft: Boolean, val gen: Int)

private sealed interface Incoming {
    data class Audio(val pcm: ByteArray) : Incoming
    data object Finish : Incoming
    class Sync(val done: CompletableDeferred<Unit>) : Incoming
}

private fun draftStep(code: String): Int = when {
    code == "zh" || code == "yue" || code.startsWith("zh") -> 4
    code == "ja" -> 6
    code == "ko" -> 5
    else -> 12
}

private fun spaced(code: String): Boolean = code != "zh" && code != "yue" && code != "ja" && !code.startsWith("zh")

private fun trace(message: String) {
    try {
        Log.i("CscAsr", message)
    } catch (_: RuntimeException) {
        // JVM unit tests ship an android.util.Log that throws.
    }
}
