package com.csc.translate

import com.csc.translate.data.HearSource
import com.csc.translate.data.Pipeline
import com.csc.translate.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun activeUrlFollowsTheSelectedPipeline() {
        val settings = UserSettings(
            baseUrl = "http://192.168.1.241:8080/",
            tomoruUrl = "http://192.168.1.241:8090/",
        )
        assertEquals("http://192.168.1.241:8080", settings.activeUrl())
        assertEquals(
            "http://192.168.1.241:8090",
            settings.copy(pipeline = Pipeline.Tomoru).activeUrl(),
        )
        assertEquals("", settings.copy(pipeline = Pipeline.Tomoru, tomoruUrl = "  ").activeUrl())
    }

    @Test
    fun hearSourceDefaultsToTheMicrophone() {
        assertEquals(HearSource.Mic, UserSettings().hearSource)
        assertEquals(HearSource.System, HearSource.from("system"))
        assertEquals(HearSource.Mic, HearSource.from(null))
    }
}
