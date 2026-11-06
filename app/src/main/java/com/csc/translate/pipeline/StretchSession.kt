package com.csc.translate.pipeline

import android.util.Log
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
private const val VOLATILE = (0.5 * RATE).toInt()
private const val GAP = RATE
private const val LONGEST = 15 * RATE
private const val DRAFT_GAP_MS = 600L

/**
 * The phone's ASR + translate path. It hears 16 kHz PCM, cuts speech the way Tomoru's Qwen3-ASR
 * stretch runner does, asks [decode] for the text, and [translate] for each line.
 */
internal class StretchSession(
    parent: CoroutineScope,
    private val srcLang: String,
    private val tgtLang: String,
    private val decode: suspend (ByteArray) -> String,
    private val translate: suspend (text: String, draft: Boolean) -> String,
    floor: Double = 0.02,
    private val voice: Voice = Level(floor),
    private val onView: (src: String, tgt: String) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
    private val detector = Detector(voice)
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
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
    private var decodedAt = 0
    private var generation = 0
    private var pendingDecode: Pending? = null

    private val lines = mutableListOf<Line>()
    private var closed = 0
    private var openLines = 0
    private var pending = ""
    private var draft = ""
    private var draftBusy = false
    private var draftLen = 0
    private var draftAt = 0L
    private var draftGen = 0
    private var shown = ""
    private var discarded = 0

    private val job = scope.launch {
        try {
            run()
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) onError(t)
        } finally {
            finished.complete(Unit)
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

    private suspend fun run() {
        while (true) {
            val first = incoming.receive()
            val batch = mutableListOf(first)
            while (true) {
                val next = incoming.tryReceive().getOrNull() ?: break
                batch += next
            }
            var stop = false
            for (item in batch) {
                when (item) {
                    is Incoming.Audio -> feed(item.pcm)
                    Incoming.Finish -> stop = true
                }
            }
            val req = takePending()
            if (req != null) apply(req, decodeLogged(req))
            if (stop) {
                val last = takePending() ?: finalNow()
                if (last != null) apply(last, decodeLogged(last))
                val deadline = System.nanoTime() + 30_000_000_000L
                while (inflight.get() > 0 && System.nanoTime() < deadline) delay(10)
                return
            }
        }
    }

    private suspend fun callDecode(pcm: ByteArray): String =
        withContext(Dispatchers.IO) { decode(pcm) }

    private suspend fun decodeLogged(req: Pending): String {
        val seconds = req.pcm.size / 2f / RATE
        val started = System.nanoTime()
        val text = callDecode(req.pcm)
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
        val gap = if (open && (ended || stepped - start >= LONGEST)) 0 else GAP
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
                decodedAt = stepped
                generation++
                trace("stretch open")
            }
            speaking && open && stepped - start >= LONGEST * 2 -> {
                trace("stretch cut ${"%.1f".format((stepped - start) / RATE.toFloat())}s")
                queue(final = true, end = stepped)
                start = stepped
                decodedAt = stepped
                ended = false
                open = true
                generation++
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
        val audio = slice(start, end)
        if (audio.isEmpty()) {
            if (final) open = false
            return
        }
        val req = Pending(audio, final, generation)
        val current = pendingDecode
        if (current == null || final || !current.final) pendingDecode = req
        if (final) open = false
    }

    private fun takePending(): Pending? {
        val req = pendingDecode
        pendingDecode = null
        return req
    }

    private fun finalNow(): Pending? {
        if (!open) return null
        val req = Pending(slice(start, total), final = true, generation)
        open = false
        return if (req.pcm.isEmpty()) null else req
    }

    private fun apply(req: Pending, text: String) {
        if (req.generation != generation && !req.final) {
            trace("drop partial")
            return
        }
        val (sentences, tail) = if (req.final) {
            val (done, rest) = splitHypothesis(text)
            val all = done.toMutableList()
            if (rest.any { it.isLetterOrDigit() }) all += rest.trim()
            all to ""
        } else {
            splitHypothesis(text)
        }
        val jobs = mutableListOf<Work>()
        var view: Pair<String, String>? = null
        synchronized(lock) {
            if (!req.final && req.generation != generation) return
            ended = !req.final && sentences.isNotEmpty()
            val from = closed
            val previous = lines.drop(from).map { it.src to it.tgt }
            while (lines.size > from) lines.removeAt(lines.lastIndex)
            if (sentences.map { it } != previous.map { it.first } || req.final) draftGen++
            openLines = 0
            sentences.forEachIndexed { index, sentence ->
                val old = previous.getOrNull(index)
                val kept = old?.first == sentence
                lines += Line(sentence, if (kept) old.second else "")
                openLines++
                if (!kept && sentence.isNotBlank()) {
                    jobs += Work(lines.lastIndex, sentence, draft = false, gen = draftGen)
                }
            }
            if (req.final) {
                closed = lines.size
                openLines = 0
                pending = ""
                draft = ""
                draftLen = 0
                draftBusy = false
            } else {
                pending = tail
                maybeDraft()?.let { jobs += it }
            }
            view = source() to target()
        }
        view?.let { (src, tgt) ->
            if (shown.isNotEmpty() && src.length + 12 < shown.length) {
                trace("source shrank ${shown.length} -> ${src.length} ${src.takeLast(80)}")
            }
            shown = src
            onView(src, tgt)
        }
        jobs.forEach { send(it) }
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

    private fun send(work: Work) {
        inflight.incrementAndGet()
        scope.launch {
            try {
                val out = withContext(Dispatchers.IO) { translate(work.text, work.draft) }
                var view: Pair<String, String>? = null
                synchronized(lock) {
                    if (work.draft) {
                        if (work.gen == draftGen && out.isNotBlank()) draft = out
                        draftBusy = false
                        val next = maybeDraft()
                        view = source() to target()
                        if (next != null) send(next)
                    } else if (out.isNotBlank() && work.index in lines.indices && lines[work.index].src == work.text) {
                        lines[work.index].tgt = out
                        view = source() to target()
                    }
                }
                view?.let { (src, tgt) -> onView(src, tgt) }
            } catch (_: Throwable) {
                synchronized(lock) { if (work.draft) draftBusy = false }
            } finally {
                inflight.decrementAndGet()
            }
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
            if (line.tgt.isBlank()) continue
            if (isNotEmpty()) append('\n')
            append(line.tgt)
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

private data class Line(val src: String, var tgt: String)

private data class Pending(val pcm: ByteArray, val final: Boolean, val generation: Int)

private data class Work(val index: Int, val text: String, val draft: Boolean, val gen: Int)

private sealed interface Incoming {
    data class Audio(val pcm: ByteArray) : Incoming
    data object Finish : Incoming
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
