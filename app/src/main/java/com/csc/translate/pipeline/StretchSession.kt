package com.csc.translate.pipeline

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
 * Tomoru's live session does. A line that does not fit is left untranslated. Only a locked
 * sentence is sent to [translate]; the open Chinese tail stays untranslated until it locks.
 * Tokens of a locked line append on screen as they stream in.
 */
internal class StretchSession(
    parent: CoroutineScope,
    private val srcLang: String,
    private val tgtLang: String,
    private val decode: suspend (pcm: ByteArray, finish: Boolean, context: String) -> String,
    private val translate: suspend (text: String, onPartial: (String) -> Unit) -> String,
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
        val view: Pair<String, String>
        synchronized(lock) {
            for (sentence in newly) {
                val line = Line(sentence)
                lines += line
                inflight.incrementAndGet()
                if (translations.trySend(Work(lines.lastIndex, sentence)).isFailure) {
                    inflight.decrementAndGet()
                }
            }
            pending = hypothesis.pending
            view = source() to target()
        }
        val (src, tgt) = view
        if (shown.isNotEmpty() && src.length + 12 < shown.length) {
            trace("source shrank ${shown.length} -> ${src.length} ${src.takeLast(80)}")
        }
        shown = src
        onView(src, tgt)
    }

    private suspend fun translateFinal(work: Work) {
        try {
            val out = translate(work.text) { partial ->
                val view = synchronized(lock) { growTarget(work, partial) }
                view?.let { (src, tgt) -> onView(src, tgt) }
            }
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
            // Keep any tokens already shown; a failed line with none stays blank.
        } finally {
            inflight.decrementAndGet()
        }
    }

    /** The caller holds [lock]. Grows this line's translation; never rewrites a longer prefix. */
    private fun growTarget(work: Work, partial: String): Pair<String, String>? {
        val line = lines.getOrNull(work.index) ?: return null
        if (line.src != work.text) return null
        if (partial.isEmpty()) return null
        if (line.tgt.isNotEmpty() && !partial.startsWith(line.tgt)) return null
        if (partial == line.tgt) return null
        line.tgt = partial
        return source() to target()
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
            if (line.tgt.isBlank()) continue
            if (isNotEmpty()) append('\n')
            append(line.tgt)
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
)

private data class Pending(
    val pcm: ByteArray,
    val final: Boolean,
    val generation: Int,
    val alignPrefix: String,
    val end: Int,
)

private data class Work(val index: Int, val text: String)

private sealed interface Incoming {
    data class Audio(val pcm: ByteArray) : Incoming
    data object Finish : Incoming
    class Sync(val done: CompletableDeferred<Unit>) : Incoming
}

private fun trace(message: String) {
    try {
        Log.i("CscAsr", message)
    } catch (_: RuntimeException) {
        // JVM unit tests ship an android.util.Log that throws.
    }
}
