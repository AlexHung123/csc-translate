package com.csc.translate

import com.csc.translate.pipeline.CaptionLine
import com.csc.translate.pipeline.liveSourceRows
import com.csc.translate.pipeline.liveTargetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWindowTest {
    @Test
    fun sourceKeepsTheLatestLockedLinesAndPending() {
        val lines = (1L..12L).map { CaptionLine(id = it, src = "中$it。", tgt = "En$it.") }
        val rows = liveSourceRows(lines, pending = "还在说", pendingId = 99L, window = 10)
        assertEquals((3L..12L).toList() + 99L, rows.map { it.id })
        assertEquals("还在说", rows.last().text)
        assertTrue(rows.last().typing)
        assertTrue(rows.dropLast(1).none { it.typing })
    }

    @Test
    fun lastLockedSourceTypesWhenThereIsNoPending() {
        val lines = listOf(
            CaptionLine(id = 1, src = "一。", tgt = "One."),
            CaptionLine(id = 2, src = "二。", tgt = "Two."),
        )
        val rows = liveSourceRows(lines, pending = "", pendingId = 0L, window = 10)
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertFalse(rows[0].typing)
        assertTrue(rows[1].typing)
    }

    @Test
    fun targetTypesTheTranslatingLine() {
        val lines = (1L..3L).map { CaptionLine(id = it, src = "中$it。", tgt = "En$it.") }
        val rows = liveTargetRows(lines, translatingId = 2L, window = 2)
        assertEquals(listOf(2L, 3L), rows.map { it.id })
        assertTrue(rows[0].typing)
        assertFalse(rows[1].typing)
    }

    @Test
    fun targetKeepsAnEmptyLastLineWhileItTypes() {
        val lines = listOf(
            CaptionLine(id = 1, src = "一。", tgt = "One."),
            CaptionLine(id = 2, src = "二。", tgt = ""),
        )
        val rows = liveTargetRows(lines, translatingId = 2L, window = 10)
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertEquals("", rows.last().text)
        assertTrue(rows.last().typing)
    }
}
