package com.csc.translate

import com.csc.translate.audio.downmixToMono
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmMixTest {
    @Test
    fun stereoAveragesToMono() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2)
            .putShort(4)
            .putShort(10)
            .putShort(20)
            .array()
        val mono = downmixToMono(pcm, 2)
        val out = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, mono.size)
        assertEquals(3, out.getShort(0).toInt())
        assertEquals(15, out.getShort(2).toInt())
    }

    @Test
    fun monoIsCopied() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val out = downmixToMono(pcm, 1)
        assertEquals(pcm.toList(), out.toList())
        pcm[0] = 9
        assertEquals(1, out[0].toInt())
    }
}
