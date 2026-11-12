package com.csc.translate

import com.csc.translate.ui.FocusSpan
import com.csc.translate.ui.advanceRevealed
import com.csc.translate.ui.focusPieces
import com.csc.translate.ui.followCursor
import com.csc.translate.ui.integrateSpans
import com.csc.translate.ui.stableEnd
import com.csc.translate.ui.typeDelayMs
import org.junit.Assert.assertEquals
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
    fun englishRevisionKeepsTheShownLength() {
        val cursor = followCursor(
            spans = listOf(FocusSpan("Hello there", 1_000)),
            revealed = 11,
            primed = true,
            next = "Hello world",
            english = true,
            nowMs = 5_000,
        )
        assertEquals(11, cursor.revealed)
        assertEquals("Hello world", cursor.spans.joinToString("") { it.text })
    }

    @Test
    fun chineseRevisionRetypesFromTheChange() {
        val cursor = followCursor(
            spans = listOf(FocusSpan("你好世界", 1_000)),
            revealed = 4,
            primed = true,
            next = "你好中国",
            english = false,
            nowMs = 5_000,
        )
        assertEquals(2, cursor.revealed)
    }

    @Test
    fun freshEnglishTailWaitsToSettle() {
        val spans = listOf(FocusSpan("Hello.", 0), FocusSpan(" More.", 1_000))
        assertEquals(6, stableEnd(spans, nowMs = 1_200, english = true))
        assertEquals(12, stableEnd(spans, nowMs = 1_500, english = true))
    }

    @Test
    fun aLateFrameRevealsEveryCharacterTheClockEarned() {
        val text = "abcdefghijklmnopqrstuvwxyz"
        assertEquals(1, advanceRevealed(0, text, text.length, elapsedMs = 16, english = true))
        assertEquals(6, advanceRevealed(0, text, text.length, elapsedMs = 100, english = true))
    }

    @Test
    fun longBacklogTypesFaster() {
        assertTrue(typeDelayMs('中', backlog = 60) < typeDelayMs('中', backlog = 1))
    }
}
