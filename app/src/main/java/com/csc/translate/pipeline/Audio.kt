package com.csc.translate.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

const val RATE = 16_000
const val FRAME = 512

internal fun pcmToFloat(pcm: ByteArray): FloatArray {
    val n = pcm.size / 2
    val out = FloatArray(n)
    val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) {
        out[i] = buf.getShort(i * 2) / 32768f
    }
    return out
}

internal fun resample16(pcm: ByteArray, from: Int): ByteArray {
    if (from == RATE || pcm.isEmpty()) return pcm
    if (from <= 0 || pcm.size % 2 != 0) return pcm
    val n = pcm.size / 2
    val outN = (n.toLong() * RATE / from).toInt()
    if (outN < 1) return ByteArray(0)
    val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val raw = ByteArray(outN * 2)
    val dst = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until outN) {
        val pos = i * from.toDouble() / RATE
        val j = pos.toInt()
        val sample = if (j >= n - 1) {
            src.getShort((n - 1) * 2)
        } else {
            val frac = pos - j
            val a = src.getShort(j * 2).toDouble()
            val b = src.getShort((j + 1) * 2).toDouble()
            (a + (b - a) * frac).roundToInt().toShort()
        }
        dst.putShort(sample)
    }
    return raw
}

/** 16 kHz mono 16-bit PCM wrapped as a WAV file, which the ASR chat call sends. */
internal fun wavBytes(pcm: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(RATE)
    header.putInt(RATE * 2)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray())
    header.putInt(pcm.size)
    return header.array() + pcm
}

internal data class WavPcm(val pcm: ByteArray, val rate: Int, val channels: Int)

/** Reads a 16-bit PCM WAV. Anything else returns null. */
internal fun readWav(body: ByteArray): WavPcm? {
    if (body.size < 12) return null
    if (body.decodeToString(0, 4) != "RIFF" || body.decodeToString(8, 12) != "WAVE") return null
    var format = 0
    var channels = 0
    var rate = 0
    var bits = 0
    var data: ByteArray? = null
    var rest = 12
    while (rest + 8 <= body.size) {
        val id = body.decodeToString(rest, rest + 4)
        val n = ByteBuffer.wrap(body, rest + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        rest += 8
        if (n < 0 || rest + n > body.size) return null
        when (id) {
            "fmt " -> {
                if (n < 16) return null
                val fmt = ByteBuffer.wrap(body, rest, 16).order(ByteOrder.LITTLE_ENDIAN)
                format = fmt.short.toInt() and 0xffff
                channels = fmt.short.toInt() and 0xffff
                rate = fmt.int
                fmt.short
                fmt.short
                bits = fmt.short.toInt() and 0xffff
            }
            "data" -> data = body.copyOfRange(rest, rest + n)
        }
        rest += n
        if (n % 2 == 1 && rest < body.size) rest++
    }
    val samples = data ?: return null
    if (format != 1 || bits != 16 || rate <= 0 || channels <= 0) return null
    return WavPcm(samples, rate, channels)
}

internal fun mono(pcm: ByteArray, channels: Int): ByteArray {
    if (channels <= 1) return pcm
    val n = pcm.size / 2 / channels
    val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val raw = ByteArray(n * 2)
    val dst = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) {
        var sum = 0
        for (c in 0 until channels) sum += src.getShort((i * channels + c) * 2).toInt()
        dst.putShort((sum / channels).toShort())
    }
    return raw
}

/** Qwen3-ASR's reply is "language Japanese<asr_text>…", or the text alone. */
internal fun asrAnswer(raw: String): String {
    val cut = raw.indexOf("<asr_text>")
    if (cut < 0) {
        if (raw.trim().startsWith("language ")) return ""
        return raw.trim()
    }
    val head = raw.substring(0, cut).trim()
    if (head == "language None") return ""
    return raw.substring(cut + "<asr_text>".length).trim()
}

private val sentenceEnd = "。．.！!？?"

/**
 * Sentences the recognizer finished and then kept speaking after, plus the tail still open.
 * A sentence that ends the hypothesis stays in the tail until the stretch ends.
 */
internal fun splitHypothesis(text: String): Pair<List<String>, String> {
    val done = mutableListOf<String>()
    var start = 0
    for (i in text.indices) {
        if (text[i] !in sentenceEnd) continue
        val sentence = text.substring(start, i + 1).trim()
        val after = text.substring(i + 1)
        if (sentence.any { it.isLetterOrDigit() } && after.any { it.isLetterOrDigit() }) {
            done += sentence
            start = i + 1
        }
    }
    return done to text.substring(start).trim()
}

internal fun lettersOf(text: String): Int = text.count { it.isLetterOrDigit() }

/** Two lines of subtitles. A wide character, as CJK's, counts as two letters. */
internal const val MAX_WIDTH = 90

private const val TAIL_PUNCT = "。．.！!？?、，,"

/** A partial decode's own closing punctuation is not the speaker finishing. */
internal fun stripVolatileEnd(text: String): String =
    text.trimEnd { it.isWhitespace() || it in TAIL_PUNCT }

/**
 * How far [committed] reaches into [hypothesis], as a string index. Letters and digits are
 * aligned by edit distance; punctuation after the match is included. A changed word in the
 * middle does not pull the already shown sentence back onto the screen.
 */
internal fun alignEnd(committed: String, hypothesis: String): Int {
    val x = ArrayList<Char>()
    for (ch in committed) if (ch.isLetterOrDigit()) x += ch.lowercaseChar()
    val hs = ArrayList<Pair<Char, Int>>()
    for (i in hypothesis.indices) {
        val ch = hypothesis[i]
        if (ch.isLetterOrDigit()) hs += ch.lowercaseChar() to (i + 1)
    }
    if (x.isEmpty() || hs.isEmpty()) return 0
    var prev = IntArray(hs.size + 1) { it }
    var cur = IntArray(hs.size + 1)
    for (xr in x) {
        cur[0] = prev[0] + 1
        for (j in 1..hs.size) {
            val sub = prev[j - 1] + if (hs[j - 1].first == xr) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, sub)
        }
        val swap = prev
        prev = cur
        cur = swap
    }
    var best = 0
    for (j in prev.indices) if (prev[j] < prev[best]) best = j
    var end = if (best > 0) hs[best - 1].second else 0
    while (end < hypothesis.length && !hypothesis[end].isLetterOrDigit()) end++
    return end
}

internal fun alignedTail(committed: String, hypothesis: String): String {
    if (committed.isEmpty()) return hypothesis
    val cut = alignEnd(committed, hypothesis)
    return if (cut >= hypothesis.length) "" else hypothesis.substring(cut)
}

/** Index just past the first sentence end, or -1. A final may end on the mark; a partial needs speech after it. */
internal fun nextSentence(text: String, requireAfter: Boolean): Int {
    for (i in text.indices) {
        if (text[i] !in sentenceEnd) continue
        if (!text.substring(0, i + 1).any { it.isLetterOrDigit() }) continue
        if (requireAfter && !text.substring(i + 1).any { it.isLetterOrDigit() }) continue
        return i + 1
    }
    return -1
}

internal fun textWidth(text: String): Int {
    var w = 0
    for (ch in text) w += if (ch.isEastAsianWide()) 2 else 1
    return w
}

/**
 * Where to cut [text] so the head fits two lines: after a clause mark when that leaves at least
 * a third of the width, else between words. -1 when there is no such break (Chinese with no comma).
 */
internal fun cutWithin(text: String): Int {
    var fits = 0
    while (fits < text.length && textWidth(text.substring(0, fits + 1)) <= MAX_WIDTH) fits++
    if (fits >= text.length) return -1
    var clause = -1
    var word = -1
    for (i in 1 until fits) {
        if (text[i - 1].isClause() && lettersOf(text.substring(0, i)) >= 6) clause = i
        if (text[i].isWhitespace() && text[i - 1].isLetterOrDigit() && lettersOf(text.substring(0, i)) >= 6) {
            word = i
        }
    }
    return when {
        clause > 0 && textWidth(text.substring(0, clause)) * 3 >= MAX_WIDTH -> clause
        word > 0 -> word
        clause > 0 -> clause
        else -> -1
    }
}

/** Pieces a decode is compared by: one character for Han and kana, one word otherwise. */
internal fun textPieces(text: String): List<String> {
    val out = mutableListOf<String>()
    val word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) {
            out += word.toString()
            word.clear()
        }
    }
    for (ch in text) {
        when {
            ch.isKanaOrHan() || ch.isPunctuation() -> {
                flush()
                out += ch.toString()
            }
            ch.isWhitespace() -> {
                flush()
                word.append(ch)
            }
            else -> word.append(ch)
        }
    }
    flush()
    return out
}

private fun Char.isClause(): Boolean = this in "，、,；;：:"

private fun Char.isKanaOrHan(): Boolean {
    val c = code
    return c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF || c in 0x3040..0x30FF || c in 0xF900..0xFAFF
}

private fun Char.isPunctuation(): Boolean = when (Character.getType(this).toByte()) {
    Character.CONNECTOR_PUNCTUATION,
    Character.DASH_PUNCTUATION,
    Character.START_PUNCTUATION,
    Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION,
    Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}

private fun Char.isEastAsianWide(): Boolean {
    val c = code
    return c in 0x1100..0x115F ||
        c in 0x2329..0x232A ||
        c in 0x2E80..0xA4CF ||
        c in 0xAC00..0xD7A3 ||
        c in 0xF900..0xFAFF ||
        c in 0xFE10..0xFE19 ||
        c in 0xFE30..0xFE6F ||
        c in 0xFF00..0xFF60 ||
        c in 0xFFE0..0xFFE6 ||
        c in 0x20000..0x3FFFD
}
