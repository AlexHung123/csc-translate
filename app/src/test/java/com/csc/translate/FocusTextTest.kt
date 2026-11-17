package com.csc.translate

import com.csc.translate.ui.CJK_STREAM_MS
import com.csc.translate.ui.FocusSpan
import com.csc.translate.ui.LETTER_STREAM_MS
import com.csc.translate.ui.SPACE_STREAM_MS
import com.csc.translate.ui.STREAM_PREROLL_MS
import com.csc.translate.ui.advanceRevealed
import com.csc.translate.ui.focusPieces
import com.csc.translate.ui.followCursor
import com.csc.translate.ui.integrateSpans
import com.csc.translate.ui.queuedText
import com.csc.translate.ui.stableEnd
import com.csc.translate.ui.streamReady
import com.csc.translate.ui.streamReleaseAt
import com.csc.translate.ui.typeDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTextTest {
    @Test
    fun appendsANewSpan() {
        val spans = integrateSpans(listOf(FocusSpan("你好", 1_000)), "你好世界", 2_000)
        assertEquals(listOf(FocusSpan("你好", 1_000), FocusSpan("世界", 2_000)), spans)
    }

    @Test
    fun replacementKeepsTheUnchangedPrefixTime() {
        val spans = integrateSpans(
            listOf(FocusSpan("Hello there", 1_000)),
            "Hello world",
            5_000,
        )
        assertEquals(listOf(FocusSpan("Hello ", 1_000), FocusSpan("world", 5_000)), spans)
    }

    @Test
    fun recentWindowStaysFocused() {
        val spans = listOf(
            FocusSpan("旧的一句。", 0),
            FocusSpan("新的一句。", 9_000),
        )
        val pieces = focusPieces(spans, revealed = 10, nowMs = 12_000, windowMs = 10_000)
        assertEquals("旧的一句。", pieces[0].text)
        assertEquals(false, pieces[0].focused)
        assertEquals("新的一句。", pieces[1].text)
        assertEquals(true, pieces[1].focused)
    }

    @Test
    fun hidesUnrevealedCharacters() {
        val pieces = focusPieces(listOf(FocusSpan("你好世界", 0)), revealed = 2, nowMs = 0)
        assertEquals("你好", pieces.single().text)
    }

    @Test
    fun firstTextStartsUnrevealed() {
        val cursor = followCursor(
            spans = emptyList(),
            revealed = 0,
            primed = false,
            next = "你好世界",
            nowMs = 1_000,
        )
        assertEquals(0, cursor.revealed)
        assertTrue(cursor.primed)
        assertEquals("你好世界", cursor.spans.joinToString("") { it.text })
    }

    @Test
    fun englishRevisionKeepsTheShownLength() {
        val cursor = followCursor(
            spans = listOf(FocusSpan("Hello there", 1_000)),
            revealed = 11,
            primed = true,
            next = "Hello world",
            nowMs = 5_000,
        )
        assertEquals(11, cursor.revealed)
        assertEquals("Hello world", cursor.spans.joinToString("") { it.text })
    }

    @Test
    fun chineseRevisionKeepsTheShownCharacters() {
        val cursor = followCursor(
            spans = listOf(FocusSpan("你好世界", 1_000)),
            revealed = 4,
            primed = true,
            next = "你好中国",
            nowMs = 5_000,
        )
        assertEquals(4, cursor.revealed)
        assertEquals("你好中国", cursor.spans.joinToString("") { it.text })
    }

    @Test
    fun anUnrevealedTailIsReplacedInTheQueue() {
        val cursor = followCursor(
            spans = listOf(FocusSpan("你好世界", 1_000)),
            revealed = 2,
            primed = true,
            next = "你好中国",
            nowMs = 5_000,
        )
        assertEquals(2, cursor.revealed)
        val text = cursor.spans.joinToString("") { it.text }
        assertEquals("你好中国", text)
        assertEquals("中国", queuedText(text, cursor.revealed))
    }

    @Test
    fun freshEnglishTailWaitsToSettle() {
        val spans = listOf(FocusSpan("Hello.", 0), FocusSpan(" More.", 1_000))
        assertEquals(6, stableEnd(spans, nowMs = 1_200, english = true))
        assertEquals(12, stableEnd(spans, nowMs = 1_500, english = true))
    }

    @Test
    fun theQueueReleasesOneCharacterPerInterval() {
        val text = "你好世界"
        assertEquals("你好世界", queuedText(text, revealed = 0))
        assertEquals("世界", queuedText(text, revealed = 2))
        assertEquals("", queuedText(text, revealed = text.length))
        assertEquals(CJK_STREAM_MS, typeDelayMs('中'))
        assertEquals(CJK_STREAM_MS, typeDelayMs('。'))
        assertEquals(LETTER_STREAM_MS, typeDelayMs('a'))
        assertEquals(SPACE_STREAM_MS, typeDelayMs(' '))
        assertEquals(0, advanceRevealed(0, text, text.length, elapsedMs = CJK_STREAM_MS - 1))
        assertEquals(1, advanceRevealed(0, text, text.length, elapsedMs = CJK_STREAM_MS))
        assertEquals(1, advanceRevealed(0, text, text.length, elapsedMs = CJK_STREAM_MS * 8))
        assertEquals(1, advanceRevealed(0, "Hello", 5, elapsedMs = LETTER_STREAM_MS * 4))
    }

    @Test
    fun thePrerollWaitsOnce() {
        assertEquals(0L, streamReleaseAt(0L, nowMs = 1_000, hasText = false))
        val armed = streamReleaseAt(0L, nowMs = 1_000, hasText = true)
        assertEquals(1_000L + STREAM_PREROLL_MS, armed)
        assertEquals(armed, streamReleaseAt(armed, nowMs = 9_000, hasText = true))
        assertFalse(streamReady(armed, 1_000L + STREAM_PREROLL_MS - 1))
        assertTrue(streamReady(armed, 1_000L + STREAM_PREROLL_MS))
        assertFalse(streamReady(0L, 60_000))
    }
}
