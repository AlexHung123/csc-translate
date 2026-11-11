package com.csc.translate

import com.csc.translate.pipeline.Detector
import com.csc.translate.pipeline.FRAME
import com.csc.translate.pipeline.Hypothesis
import com.csc.translate.pipeline.RATE
import com.csc.translate.pipeline.StretchSession
import com.csc.translate.pipeline.Voice
import com.csc.translate.pipeline.VoiceAt
import com.csc.translate.pipeline.alignEnd
import com.csc.translate.pipeline.asrAnswer
import com.csc.translate.pipeline.splitHypothesis
import com.csc.translate.pipeline.stripVolatileEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
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
    fun voiceStartsAfterTwoLoudFramesAndStopsAfterThreeQuietOnes() {
        assertEquals(emptyList<VoiceAt>(), frames(0.9f))
        assertEquals(emptyList<VoiceAt>(), frames(0.9f, 0.4f, 0.9f))
        val started = frames(0.2f, 0.9f, 0.9f)
        assertEquals(1, started.size)
        assertTrue(started[0].on)
        assertEquals(FRAME / RATE.toDouble(), started[0].tSec, 1e-6)
        val held = frames(0.9f, 0.9f, 0.4f, 0.4f, 0.4f)
        assertEquals(1, held.size)
        val both = frames(0.9f, 0.9f, 0.2f, 0.2f, 0.2f)
        assertEquals(2, both.size)
        assertTrue(both[0].on)
        assertEquals(0.0, both[0].tSec, 1e-6)
        assertFalse(both[1].on)
        assertEquals(2 * FRAME / RATE.toDouble(), both[1].tSec, 1e-6)
    }

    @Test
    fun twoDecodesAgreeBeforeThePrefixChanges() {
        val h = Hypothesis()
        assertEquals("", h.settle("你好世界"))
        assertEquals("你好世界", h.settle("你好世界"))
        assertEquals("你好世界", h.settle("您好世界呀"))
        assertEquals("您好世界呀", h.settle("您好世界呀"))
    }

    @Test
    fun aSentenceLocksAndALaterDecodeKeepsIt() {
        val h = Hypothesis()
        h.settle("你好。我是")
        val agreed = h.settle("你好。我是")
        val locked = h.absorb(stripVolatileEnd(agreed), h.alignPrefix, final = false, quiet = false)
        assertEquals(listOf("你好。"), locked)
        assertEquals("我是", h.pending)
        h.settle("你好。我是小明")
        val grown = stripVolatileEnd(h.settle("你好。我是小明啊"))
        val more = h.absorb(grown, h.alignPrefix, final = false, quiet = false)
        assertEquals(emptyList<String>(), more)
        assertEquals("我是小明", h.pending)
        assertEquals(3, alignEnd("你好。", "你好。我是小明"))
    }

    @Test
    fun aLongLineCutsAtTheComma() {
        val h = Hypothesis()
        val text = "一".repeat(20) + "，" + "二".repeat(30)
        val locked = h.absorb(text, "", final = false, quiet = false)
        assertEquals(listOf("一".repeat(20) + "，"), locked)
        assertEquals("二".repeat(30), h.pending)
    }

    @Test
    fun aFinalTailWaitsThenJoinsTheNextStretch() {
        val h = Hypothesis()
        h.settle("你好。今天")
        val agreed = stripVolatileEnd(h.settle("你好。今天"))
        h.absorb(agreed, h.alignPrefix, final = false, quiet = false)
        val fin = h.absorb("你好。今天", h.alignPrefix, final = true, quiet = false)
        assertEquals(emptyList<String>(), fin)
        assertEquals("今天", h.pending)
        h.resetStretch()
        h.settle("天气")
        val joined = h.absorb(stripVolatileEnd(h.settle("天气")), h.alignPrefix, final = false, quiet = false)
        assertEquals(emptyList<String>(), joined)
        assertEquals("今天天气", h.pending)
        val held = h.absorb("天气", h.alignPrefix, final = true, quiet = false)
        assertEquals(emptyList<String>(), held)
        assertEquals(listOf("今天天气"), h.commitHeld())
        assertEquals("", h.pending)
    }

    @Test
    fun theTailStaysPendingUntilThePauseThenJoins() = runBlocking {
        var n = 0
        val finals = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = {
                n += 1
                if (n <= 3) "你好。今天" else "天气"
            },
            translate = { text, draft ->
                if (!draft) finals += text
                "t"
            },
        )
        session.write(sinePcm(13_000))
        session.awaitIdle()
        session.write(sinePcm(9_000))
        session.awaitIdle()
        withTimeout(5_000) { while ("你好。" !in finals) delay(10) }
        assertFalse(finals.toString(), "今天" in finals)
        assertTrue(session.snapshot().first, session.snapshot().first.contains("今天"))

        session.write(ByteArray(2_000 * 2))
        session.awaitIdle()
        assertFalse(finals.toString(), "今天" in finals)

        session.write(sinePcm(13_000))
        session.awaitIdle()
        session.write(sinePcm(9_000))
        session.awaitIdle()
        val src = session.snapshot().first
        assertTrue(src, src.contains("今天天气"))
        assertFalse(finals.toString(), "今天" in finals)

        // Voice-off is a few frames after the samples start, and the runner steps by whole frames.
        // One second of zeros ends short of the 1 s gap, so the tail stays pending.
        session.write(ByteArray(32_000 * 2))
        session.awaitIdle()
        withTimeout(5_000) { while ("今天天气" !in finals) delay(10) }
        assertFalse(finals.toString(), "今天" in finals)
        session.stop()
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

private class Scores(private val values: FloatArray) : Voice {
    private var i = 0
    override var score: Float = 0f
        private set

    override fun accept(frame: FloatArray) {
        score = if (i < values.size) values[i] else 0f
        i++
    }
}

private fun frames(vararg scores: Float): List<VoiceAt> {
    val gate = Scores(scores)
    return Detector(gate).push(FloatArray(scores.size * FRAME))
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
