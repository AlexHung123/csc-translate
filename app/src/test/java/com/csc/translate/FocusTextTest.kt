package com.csc.translate

import com.csc.translate.ui.FocusSpan
import com.csc.translate.ui.focusPieces
import com.csc.translate.ui.integrateSpans
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
    fun longBacklogTypesFaster() {
        assertTrue(typeDelayMs('中', backlog = 60) < typeDelayMs('中', backlog = 1))
    }
}
