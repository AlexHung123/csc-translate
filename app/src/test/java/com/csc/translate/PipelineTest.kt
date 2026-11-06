package com.csc.translate

import com.csc.translate.pipeline.StretchSession
import com.csc.translate.pipeline.asrAnswer
import com.csc.translate.pipeline.splitHypothesis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class PipelineTest {
    @Test
    fun answerCutsTheLanguageTag() {
        assertEquals("你好", asrAnswer("language Cantonese<asr_text>你好"))
        assertEquals("", asrAnswer("language None"))
        assertEquals("你好", asrAnswer("  你好  "))
    }

    @Test
    fun aFinishedSentenceStaysOpenUntilSpeechFollowsIt() {
        val (done, tail) = splitHypothesis("你好。我是")
        assertEquals(listOf("你好。"), done)
        assertEquals("我是", tail)
        val (none, rest) = splitHypothesis("你好。")
        assertEquals(emptyList<String>(), none)
        assertEquals("你好。", rest)
    }

    @Test
    fun loudSpeechBecomesOneTranslatedLine() = runBlocking {
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { "你好。" },
            translate = { _, _ -> "Hello." },
        )
        session.write(sinePcm(16_000))
        session.write(ByteArray(24_000 * 2))
        session.finish()
        val (src, tgt) = session.snapshot()
        assertTrue(src, src.contains("你好"))
        assertEquals("Hello.", tgt)
        session.stop()
    }
}

private fun sinePcm(n: Int): ByteArray {
    val raw = ByteArray(n * 2)
    val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) {
        val v = (0.4 * sin(2 * Math.PI * 440.0 * i / 16_000.0) * 32767).toInt()
        buf.putShort(v.coerceIn(-32767, 32767).toShort())
    }
    return raw
}
