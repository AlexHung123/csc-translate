package com.csc.translate

import com.csc.translate.net.AppJson
import com.csc.translate.net.SubmitResponse
import com.csc.translate.net.TaskPoll
import com.csc.translate.net.websocketUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun websocketFromHttp() {
        assertEquals(
            "ws://192.168.1.241:8080/translate/ws",
            websocketUrl("http://192.168.1.241:8080/"),
        )
    }

    @Test
    fun websocketFromHttps() {
        assertEquals(
            "wss://example.com/translate/ws",
            websocketUrl("https://example.com"),
        )
    }

    @Test
    fun submitResponseReadsTaskId() {
        val parsed = AppJson.decodeFromString<SubmitResponse>("""{"task_id":"abc-123"}""")
        assertEquals("abc-123", parsed.taskId)
    }

    @Test
    fun taskPollReadsEventsAndCursor() {
        val parsed = AppJson.decodeFromString<TaskPoll>(
            """{"events":[{"event":"chunk","text":"Hi"}],"next_cursor":1,"total_events":2,"done":false}""",
        )
        assertEquals(1, parsed.nextCursor)
        assertEquals(2, parsed.totalEvents)
        assertEquals("Hi", parsed.events.single().text)
        assertTrue(!parsed.done)
    }
}
