package com.csc.translate

import com.csc.translate.data.DEFAULT_RELAY_URL
import com.csc.translate.data.HearSource
import com.csc.translate.data.MeetingIds
import com.csc.translate.data.Passcodes
import com.csc.translate.data.Pipeline
import com.csc.translate.data.SHOW_PIPELINE_CHOICE
import com.csc.translate.data.UserSettings
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun activeUrlFollowsTheSelectedPipeline() {
        val settings = UserSettings(
            pipeline = Pipeline.Simul,
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
    fun pipelineChoiceIsHiddenAndDefaultsToAsr() {
        assertEquals(false, SHOW_PIPELINE_CHOICE)
        assertEquals(Pipeline.Tomoru, UserSettings().pipeline)
    }

    @Test
    fun meetingIdIsGeneratedAndGrouped() {
        val random = Random(7)
        val first = MeetingIds.generate(random)
        val second = MeetingIds.generate(Random(8))
        assertEquals(8, first.length)
        assertEquals(true, MeetingIds.isValid(first))
        assertEquals(first.take(4) + "-" + first.drop(4), MeetingIds.format(first))
        assertEquals(first, MeetingIds.canonical(MeetingIds.format(first)))
        assertEquals(false, first == second)
        assertEquals("", UserSettings().meetingId)
    }

    @Test
    fun passcodeIsEmptyOrFourToTwelveLetters() {
        assertEquals("", Passcodes.normalize("  "))
        assertEquals("Ab12", Passcodes.normalize(" Ab12 "))
        assertEquals(null, Passcodes.normalize("abc"))
        assertEquals(null, Passcodes.normalize("has space"))
        assertEquals("", UserSettings().passcode)
    }

    @Test
    fun relayUrlDefaultsToThisComputer() {
        assertEquals(DEFAULT_RELAY_URL, UserSettings().relayUrl)
        assertEquals("http://192.168.1.58:8091", UserSettings().relayUrl)
    }

    @Test
    fun shareRelayDefaultsOn() {
        assertEquals(true, UserSettings().shareRelay)
    }

    @Test
    fun hearSourceDefaultsToTheMicrophone() {
        assertEquals(HearSource.Mic, UserSettings().hearSource)
        assertEquals(HearSource.System, HearSource.from("system"))
        assertEquals(HearSource.Mic, HearSource.from(null))
    }
}
