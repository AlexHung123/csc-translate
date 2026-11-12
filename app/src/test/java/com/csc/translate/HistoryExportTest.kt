package com.csc.translate

import com.csc.translate.data.db.SessionEntity
import com.csc.translate.ui.historyFileName
import com.csc.translate.ui.historyTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryExportTest {
    @Test
    fun transcriptIncludesBothLanguages() {
        val text = historyTranscript(
            session(
                title = "软件开发",
                direction = "en-zh",
                source = "HELLO WORLD.",
                target = "这是测试",
            ),
        )
        assertTrue(text.startsWith("軟件開發\n"))
        assertTrue(text.contains("English → Cantonese"))
        assertTrue(text.contains("Hello world."))
        assertTrue(text.contains("這是測試"))
    }

    @Test
    fun fileNameStripsReservedCharacters() {
        val name = historyFileName(
            session(title = "a/b:c*d?\"e<f>g|h", startedAt = 0L),
        )
        val date = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(0L))
        assertEquals("$date a b c d e f g h.txt", name)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('"'))
        assertFalse(name.contains('<'))
        assertFalse(name.contains('>'))
        assertFalse(name.contains('|'))
    }

    private fun session(
        title: String = "session",
        direction: String = "en-zh",
        source: String = "",
        target: String = "",
        startedAt: Long = 1_700_000_000_000L,
    ) = SessionEntity(
        id = "1",
        direction = direction,
        title = title,
        sourceText = source,
        targetText = target,
        startedAt = startedAt,
        durationMs = 3_000L,
    )
}
