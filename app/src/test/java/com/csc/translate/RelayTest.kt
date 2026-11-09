package com.csc.translate

import com.csc.translate.net.AppJson
import com.csc.translate.net.RelayPicture
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayTest {
    @Test
    fun pictureFieldsMatchTheRelay() {
        val json = AppJson.encodeToString(
            RelayPicture(
                phase = "live",
                direction = "en-zh",
                displayMode = "split",
                typeScale = "medium",
                sourceText = "He said hello.",
                targetText = "他说，你好。",
                paused = false,
                status = "Listening",
            ),
        )
        val decoded = AppJson.decodeFromString<RelayPicture>(json)
        assertEquals("live", decoded.phase)
        assertEquals("split", decoded.displayMode)
        assertEquals("He said hello.", decoded.sourceText)
        assertEquals("他说，你好。", decoded.targetText)
        assertEquals(false, decoded.paused)
        assertEquals(false, json.contains("passcode"))
    }
}
