package com.csc.translate

import com.csc.translate.net.ServerEvent
import com.csc.translate.session.TitleFormatter
import com.csc.translate.session.Transcript
import com.csc.translate.session.applyEvent
import com.csc.translate.session.sourceForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
    @Test
    fun appendsChunkTextAndSource() {
        val after = Transcript().applyEvent(
            ServerEvent(event = "chunk", text = "Hello", sourceText = "你好"),
        )
        assertEquals("你好", after.sourceRaw)
        assertEquals("Hello", after.targetRaw)
    }

    @Test
    fun sourceTextFullReplaces() {
        val after = Transcript(sourceRaw = "他说")
            .applyEvent(ServerEvent(event = "chunk", text = "Hi", sourceTextFull = "他说，你好。", sourceText = ""))
        assertEquals("他说，你好。", after.sourceRaw)
        assertEquals("Hi", after.targetRaw)
    }

    @Test
    fun doneReplacesBoth() {
        val after = Transcript(sourceRaw = "partial", targetRaw = "part")
            .applyEvent(ServerEvent(event = "done", sourceText = "全文。", fullText = "Full."))
        assertEquals("全文。", after.sourceRaw)
        assertEquals("Full.", after.targetRaw)
    }

    @Test
    fun titleUsesFirstSourceSentence() {
        assertEquals(
            "讨论培训路径与春节档",
            TitleFormatter.title("讨论培训路径与春节档。后面的话", "Later"),
        )
    }

    @Test
    fun titleFallsBackWhenEmpty() {
        assertEquals(TitleFormatter.EMPTY, TitleFormatter.title("  ", ""))
        assertEquals("Hello world", TitleFormatter.title("", "Hello world"))
    }

    @Test
    fun englishDisplaySentenceCasesAllCaps() {
        val out = sourceForDisplay("HELLO WORLD. NEXT.", "en-zh")
        assertEquals("Hello world. Next.", out)
    }

    @Test
    fun englishDisplayLeavesMixedCase() {
        assertEquals("Hello NASA", sourceForDisplay("Hello NASA", "en-zh"))
    }
}
