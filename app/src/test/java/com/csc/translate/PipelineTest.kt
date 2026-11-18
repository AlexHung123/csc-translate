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
import com.csc.translate.pipeline.pcm16ToFloat32
import com.csc.translate.pipeline.splitHypothesis
import com.csc.translate.pipeline.readChatStream
import com.csc.translate.pipeline.streamCall
import com.csc.translate.pipeline.streamChoiceDelta
import com.csc.translate.pipeline.streamSessionId
import com.csc.translate.pipeline.streamStart
import com.csc.translate.pipeline.streamText
import com.csc.translate.pipeline.stripVolatileEnd
import okio.Buffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
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
    fun theLatestDecodeShowsAndTheUnfixedTailCanChange() {
        val h = Hypothesis()
        assertEquals("", h.settle(""))
        assertEquals("你好世界", h.settle("你好世界"))
        assertTrue(h.provisional)
        assertEquals("", h.stable)
        assertEquals("您好世界呀", h.settle("您好世界呀"))
        assertTrue(h.provisional)
        assertEquals("一二三四五六七八", h.settle("一二三四五六七八"))
        assertFalse(h.provisional)
        assertEquals("一二三", h.stable)
    }

    @Test
    fun aProvisionalPartialDoesNotLockASentence() {
        val h = Hypothesis()
        val first = stripVolatileEnd(h.settle("你好。我是"))
        assertTrue(h.provisional)
        h.preview(first)
        assertEquals("你好。我是", h.pending)
        val shown = stripVolatileEnd(h.settle("你好。我是小明啊今天"))
        assertFalse(h.provisional)
        val locked = h.absorb(stripVolatileEnd(h.stable), h.alignPrefix, final = false, quiet = false)
        h.preview(shown)
        assertEquals(listOf("你好。"), locked)
        assertTrue(h.pending, h.pending.contains("我是"))
    }

    @Test
    fun aSentenceLocksAndALaterDecodeKeepsIt() {
        val h = Hypothesis()
        val shown = stripVolatileEnd(h.settle("你好。我是小明啊今天"))
        val locked = h.absorb(stripVolatileEnd(h.stable), h.alignPrefix, final = false, quiet = false)
        h.preview(shown)
        assertEquals(listOf("你好。"), locked)
        assertTrue(h.pending, h.pending.contains("我是"))
        val grown = stripVolatileEnd(h.settle("你好。我是小明啊今天呀"))
        val more = h.absorb(stripVolatileEnd(h.stable), h.alignPrefix, final = false, quiet = false)
        h.preview(grown)
        assertEquals(emptyList<String>(), more)
        assertTrue(h.pending, h.pending.contains("我是小明"))
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
        val shown = stripVolatileEnd(h.settle("你好。今天天气真好"))
        h.absorb(stripVolatileEnd(h.stable), h.alignPrefix, final = false, quiet = false)
        h.preview(shown)
        val fin = h.absorb("你好。今天天气真好", h.alignPrefix, final = true, quiet = false)
        assertEquals(emptyList<String>(), fin)
        assertTrue(h.pending, h.pending.contains("今天"))
        h.resetStretch()
        h.preview(stripVolatileEnd(h.settle("天气")))
        assertTrue(h.pending, h.pending.contains("今天天气"))
        val held = h.absorb("天气", h.alignPrefix, final = true, quiet = false)
        assertEquals(emptyList<String>(), held)
        val committed = h.commitHeld()
        assertEquals(1, committed.size)
        assertTrue(committed.toString(), committed.first().contains("今天天气"))
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
            decode = { _, _, _ ->
                n += 1
                if (n <= 3) "你好。今天天气真好" else "你好。今天天气真好天气"
            },
            translate = { text, _ ->
                finals += text
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
        assertTrue(src, src.contains("今天") && src.contains("天气"))
        assertFalse(finals.toString(), "今天天气" in finals)

        // Voice-off is a few frames after the samples start, and the runner steps by whole frames.
        // One second of zeros ends short of the 1 s gap, so the tail stays pending.
        session.write(ByteArray(32_000 * 2))
        session.awaitIdle()
        withTimeout(5_000) { while (finals.none { "今天天气" in it }) delay(10) }
        assertFalse(finals.toString(), "今天" in finals)
        session.stop()
    }

    @Test
    fun aPeriodNeedsAShortPauseBeforeTheStretchEnds() = runBlocking {
        var finishes = 0
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, finish, _ ->
                if (finish) finishes += 1
                "你好。我是小明啊今天"
            },
            translate = { _, _ -> "t" },
        )
        session.write(sinePcm(24_000))
        session.awaitIdle()
        session.write(ByteArray(2_000 * 2))
        session.awaitIdle()
        assertEquals(0, finishes)
        session.write(ByteArray(12_000 * 2))
        session.awaitIdle()
        assertTrue(finishes >= 1)
        session.stop()
    }

    @Test
    fun loudSpeechBecomesOneTranslatedLine() = runBlocking {
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, _ -> "你好。" },
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

    @Test
    fun finishedLinesAreTranslatedOneAtATime() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, _ -> "一一一一。二二二二。三三三三。還有尾巴啊" },
            translate = { text, _ ->
                entered += text
                hold.await()
                "T:$text"
            },
        )
        session.write(sinePcm(24_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        withTimeout(5_000) {
            while (entered.isEmpty()) delay(10)
        }
        assertEquals(listOf("一一一一。"), entered.toList())
        assertTrue(session.snapshot().first, session.snapshot().first.contains("二二二二"))
        hold.complete(Unit)
        session.write(ByteArray(24_000 * 2))
        session.finish()
        assertTrue(entered.toString(), entered.contains("二二二二。"))
        session.stop()
    }

    @Test
    fun aFullQueueLeavesTheExtraLineUntranslated() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            translationQueue = 1,
            decode = { _, _, _ -> "一一一一。二二二二。三三三三。還有尾巴啊今天" },
            translate = { text, _ ->
                entered += text
                hold.await()
                "T:$text"
            },
        )
        session.write(sinePcm(24_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        withTimeout(5_000) {
            while (session.snapshot().first.contains("三三三三").not()) delay(10)
        }
        assertFalse(entered.toString(), "三三三三" in entered.joinToString())
        hold.complete(Unit)
        session.write(ByteArray(24_000 * 2))
        session.finish()
        val (src, tgt) = session.snapshot()
        assertTrue(src, src.contains("三三三三"))
        assertFalse(entered.toString(), "三三三三" in entered.joinToString())
        assertFalse(tgt, tgt.contains("T:三三三三"))
        session.stop()
    }

    @Test
    fun pendingChineseIsNotTranslated() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, _ -> "你好世界今天" },
            translate = { text, _ ->
                seen += text
                "T"
            },
        )
        session.write(sinePcm(24_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        assertTrue(session.snapshot().first, session.snapshot().first.contains("你好"))
        assertEquals("", session.snapshot().second)
        assertTrue(seen.toString(), seen.isEmpty())
        session.stop()
    }

    @Test
    fun aLockedLineWaitsForTheFinalTranslation() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        var punctuate = false
        val finals = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, _ -> if (punctuate) "你好世界。還有尾巴啊今天" else "你好世界今天" },
            translate = { text, _ ->
                finals += text
                hold.await()
                "正式"
            },
        )
        session.write(sinePcm(24_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        assertEquals("", session.snapshot().second)
        assertTrue(finals.toString(), finals.isEmpty())
        punctuate = true
        session.write(sinePcm(16_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        withTimeout(5_000) {
            while (finals.isEmpty()) delay(10)
        }
        assertEquals(listOf("你好世界。"), finals.toList())
        assertEquals("", session.snapshot().second)
        hold.complete(Unit)
        session.write(ByteArray(24_000 * 2))
        session.finish()
        assertTrue(session.snapshot().second, session.snapshot().second.contains("正式"))
        session.stop()
    }

    @Test
    fun aHungLineDoesNotBlockTheNextTranslation() = runBlocking {
        val entered = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            translateLimitMs = 80,
            decode = { _, _, _ -> "一一一一。二二二二。" },
            translate = { text, onPartial ->
                entered += text
                if ("一" in text) delay(10_000)
                onPartial("T:$text")
                "T:$text"
            },
        )
        session.write(sinePcm(16_000))
        session.write(ByteArray(24_000 * 2))
        session.finish()
        withTimeout(5_000) {
            while (session.snapshot().second.contains("T:二二二二").not()) delay(10)
        }
        assertTrue(entered.toString(), entered.any { "一" in it })
        assertTrue(entered.toString(), entered.any { "二" in it })
        assertFalse(session.snapshot().second, session.snapshot().second.contains("T:一一一一"))
        session.stop()
    }

    @Test
    fun aLockedLineStreamsTheTranslation() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, _ -> "你好。" },
            translate = { _, onPartial ->
                onPartial("Hel")
                onPartial("Hello.")
                "Hello."
            },
            onView = { _, tgt -> if (tgt.isNotBlank()) seen += tgt },
        )
        session.write(sinePcm(16_000))
        session.write(ByteArray(24_000 * 2))
        session.finish()
        assertTrue(seen.toString(), seen.contains("Hel"))
        assertTrue(seen.toString(), seen.last().contains("Hello."))
        session.stop()
    }

    @Test
    fun chatStreamAppendsEachDelta() {
        val parts = mutableListOf<String>()
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo.\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val out = readChatStream(Buffer().writeUtf8(sse)) { parts += it }
        assertEquals("Hello.", out)
        assertEquals(listOf("Hel", "Hello."), parts)
    }

    @Test
    fun chatStreamReadsANonStreamedReply() {
        val parts = mutableListOf<String>()
        val raw = """{"choices":[{"message":{"content":"Hello."}}]}"""
        assertEquals("Hello.", readChatStream(Buffer().writeUtf8(raw)) { parts += it })
        assertEquals(listOf("Hello."), parts)
    }

    @Test
    fun chatStreamDeltaSkipsEmptyAndReadsContent() {
        assertEquals("Hi", streamChoiceDelta("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals(null, streamChoiceDelta("""{"choices":[{"delta":{}}]}"""))
        try {
            streamChoiceDelta("""{"error":{"message":"nope"}}""")
            org.junit.Assert.fail("expected error")
        } catch (t: Throwable) {
            assertTrue(t.message, t.message!!.contains("nope"))
        }
    }

    @Test
    fun aStretchSendsOnlyAudioTheServerHasNotHeard() = runBlocking {
        val sizes = Collections.synchronizedList(mutableListOf<Int>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { pcm, _, _ ->
                sizes += pcm.size
                "甲"
            },
            translate = { _, _ -> "t" },
        )
        session.write(sinePcm(16_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        assertTrue(sizes.toString(), sizes.size >= 2)
        assertTrue(sizes.toString(), sizes.sum() <= 16_000 * 2 * 2)
        session.stop()
    }

    @Test
    fun aFailedPartialKeepsTheSessionAlive() = runBlocking {
        var n = 0
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, _ ->
                n += 1
                if (n == 1) error("boom")
                "你好世界今天天气"
            },
            translate = { _, _ -> "t" },
        )
        session.write(sinePcm(16_000))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        assertTrue(session.snapshot().first, session.snapshot().first.contains("你好"))
        session.stop()
    }

    @Test
    fun aLaterStreamReceivesLockedTextAsContext() = runBlocking {
        val contexts = Collections.synchronizedList(mutableListOf<String>())
        val session = StretchSession(
            parent = this,
            srcLang = "zh",
            tgtLang = "en",
            decode = { _, _, context ->
                contexts += context
                "一一一一。二二二二。三三三三。還有尾巴啊"
            },
            translate = { _, _ -> "t" },
        )
        session.write(sinePcm(24_000))
        session.awaitIdle()
        session.write(ByteArray(32_000 * 2))
        session.awaitIdle()
        session.write(sinePcm(16_000))
        session.awaitIdle()
        assertTrue(contexts.toString(), contexts.any { it.contains("一一一一") })
        session.stop()
    }

    @Test
    fun salvageKeepsTheCaptionWhenTheStreamDrops() {
        val h = Hypothesis()
        h.preview(stripVolatileEnd(h.settle("你好世界今天")))
        h.salvageUnfixed()
        h.preview(stripVolatileEnd(h.settle("天气")))
        assertTrue(h.pending, h.pending.contains("你好世界今天天气"))
    }

    @Test
    fun pcm16BecomesUnitFloat32() {
        val raw = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(16384)
            .putShort(-32768)
            .array()
        val floats = ByteBuffer.wrap(pcm16ToFloat32(raw)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, floats.float, 1e-6f)
        assertEquals(0.5f, floats.float, 1e-4f)
        assertEquals(-1f, floats.float, 1e-4f)
    }

    @Test
    fun streamResponsesExposeTheSessionAndTheText() {
        assertEquals("abc", streamSessionId("""{"session_id":"abc"}"""))
        assertEquals("你好", streamText("""{"language":"Cantonese","text":"你好"}"""))
        assertEquals("", streamText("""{"text":"  "}"""))
        assertEquals(
            "http://192.168.1.241:8000/api/chunk?session_id=abc",
            streamCall("http://192.168.1.241:8000", "api/chunk", "abc"),
        )
        assertEquals("http://192.168.1.241:8000/api/start", streamStart("http://192.168.1.241:8000", "  "))
        assertEquals(
            "http://192.168.1.241:8000/api/start?context=" +
                URLEncoder.encode("你好。今天", StandardCharsets.UTF_8.name()),
            streamStart("http://192.168.1.241:8000", "你好。今天"),
        )
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
