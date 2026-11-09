package com.csc.translate.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class RelayPicture(
    val phase: String,
    val direction: String,
    val displayMode: String,
    val typeScale: String,
    val sourceText: String,
    val targetText: String,
    val paused: Boolean,
    val status: String,
)

data class RelayRoom(
    val id: String,
    val watchUrl: String,
)

class RelayClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun open(baseUrl: String, roomId: String, passcode: String, picture: RelayPicture): RelayRoom {
        val created = json.decodeFromString<RelayCreated>(post(sessionsUrl(baseUrl), picture, roomId, passcode))
        val id = created.id.trim().ifBlank { roomId.trim() }
        if (id.isEmpty()) error("relay returned an empty room")
        val watch = created.watchUrl.trim().ifBlank { "${baseUrl.trimEnd('/')}/watch/$id" }
        return RelayRoom(id = id, watchUrl = watch)
    }

    fun update(baseUrl: String, room: String, passcode: String, picture: RelayPicture) {
        post("${sessionsUrl(baseUrl)}/$room", picture, roomId = null, passcode = passcode)
    }

    private fun post(url: String, picture: RelayPicture, roomId: String?, passcode: String): String {
        val fields = json.parseToJsonElement(json.encodeToString(picture)).jsonObject.toMutableMap()
        if (!roomId.isNullOrBlank()) fields["id"] = JsonPrimitive(roomId)
        fields["passcode"] = JsonPrimitive(passcode)
        val body = JsonObject(fields).toString().toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()
        val call = client.newCall(request)
        call.timeout().timeout(4, TimeUnit.SECONDS)
        return call.execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("relay HTTP ${response.code}")
            text
        }
    }

    private fun sessionsUrl(baseUrl: String): String = "${baseUrl.trim().trimEnd('/')}/sessions"

    @Serializable
    private data class RelayCreated(
        val id: String = "",
        val watchUrl: String = "",
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
