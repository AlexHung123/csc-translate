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
