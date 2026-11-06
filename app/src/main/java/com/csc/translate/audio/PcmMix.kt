package com.csc.translate.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Averages interleaved 16-bit PCM down to mono. The returned buffer is safe to keep. */
fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
    if (channels <= 1) return pcm.copyOf()
    val frames = pcm.size / 2 / channels
    if (frames <= 0) return ByteArray(0)
    val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val raw = ByteArray(frames * 2)
    val dst = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) sum += src.getShort((i * channels + c) * 2).toInt()
        dst.putShort((sum / channels).toShort())
    }
    return raw
}
