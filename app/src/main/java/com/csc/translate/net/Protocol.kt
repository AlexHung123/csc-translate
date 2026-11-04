package com.csc.translate.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

@Serializable
data class Health(
    val status: String = "unknown",
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("waiting_sessions") val waitingSessions: Int = 0,
    @SerialName("max_sessions") val maxSessions: Int = 4,
    @SerialName("vad_available") val vadAvailable: Boolean = true,
    @SerialName("tts_available") val ttsAvailable: Boolean = false,
)

@Serializable
data class ServiceConfig(
    val modes: List<String> = emptyList(),
    @SerialName("vad_available") val vadAvailable: Boolean = true,
    @SerialName("tts_available") val ttsAvailable: Boolean = false,
    val defaults: ServiceDefaults? = null,
    @SerialName("response_modes") val responseModes: Map<String, ResponseModeInfo> = emptyMap(),
    val mock: Boolean = false,
)

@Serializable
data class ServiceDefaults(
    @SerialName("model_type") val modelType: String? = null,
    @SerialName("response_mode") val responseMode: String? = null,
    val vad: Boolean? = null,
    val tts: Boolean? = null,
)

@Serializable
data class ResponseModeInfo(
    val label: String? = null,
    @SerialName("chunk_ms") val chunkMs: Int? = null,
)

@Serializable
data class ServerEvent(
    val event: String,
    val text: String? = null,
    @SerialName("source_text") val sourceText: String? = null,
    @SerialName("source_text_full") val sourceTextFull: String? = null,
    @SerialName("full_text") val fullText: String? = null,
    val direction: String? = null,
    @SerialName("model_type") val modelType: String? = null,
    @SerialName("source_text_enabled") val sourceTextEnabled: Boolean? = null,
    val detail: String? = null,
    @SerialName("received_ms") val receivedMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    val action: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val complete: Boolean? = null,
)

@Serializable
data class SubmitResponse(
    @SerialName("task_id") val taskId: String,
)

@Serializable
data class TaskPoll(
    val events: List<ServerEvent> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Int = 0,
    @SerialName("total_events") val totalEvents: Int = 0,
    val done: Boolean = false,
)

data class Handshake(
    val direction: String,
    val sampleRate: Int,
    val responseMode: String,
    val vad: Boolean,
) {
    fun toJson(): String = buildJsonObject {
        put("direction", direction)
        put("input_format", "s16le")
        put("input_sample_rate", sampleRate)
        put("input_channels", 1)
        put("response_mode", responseMode)
        put("vad", vad)
        put("tts", false)
        put("audio_transport", "binary")
        put("input_progress", true)
    }.toString()
}

fun websocketUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val ws = when {
        trimmed.startsWith("https://", ignoreCase = true) ->
            "wss://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
        trimmed.startsWith("http://", ignoreCase = true) ->
            "ws://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
        trimmed.startsWith("wss://", ignoreCase = true) ||
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
        else -> "ws://$trimmed"
    }
    return "$ws/translate/ws"
}

fun restUrl(baseUrl: String, path: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val http = when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("ws://", ignoreCase = true) ->
            "http://" + trimmed.removePrefix("ws://").removePrefix("WS://")
        trimmed.startsWith("wss://", ignoreCase = true) ->
            "https://" + trimmed.removePrefix("wss://").removePrefix("WSS://")
        else -> "http://$trimmed"
    }
    return "$http/${path.trimStart('/')}"
}
