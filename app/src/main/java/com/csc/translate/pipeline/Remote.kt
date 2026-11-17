package com.csc.translate.pipeline

import com.csc.translate.net.restUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val json = Json { ignoreUnknownKeys = true }
private val jsonType = "application/json".toMediaType()
private val pcmType = "application/octet-stream".toMediaType()

/**
 * The streaming server has no model list. Its front page answers once the model is loaded.
 */
fun probeAsr(client: OkHttpClient, baseUrl: String) {
    val request = Request.Builder().url(restUrl(baseUrl, "")).get().build()
    execute(client, request, 8)
}

/**
 * The same three calls as qwen-asr-demo-streaming's browser client: empty POST /api/start,
 * 16 kHz little-endian float32 to /api/chunk every ~500 ms, empty POST /api/finish.
 * [transcribe] is given only the samples not sent yet. A true [finish] ends that utterance.
 * A new start may carry [context] as `?context=`, which Spark's demo must read into
 * `init_streaming_state(context=...)`. One failed HTTP call is retried on a fresh session.
 */
class QwenAsr(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    private val lock = Any()
    private var sessionId: String? = null

    fun transcribe(pcm: ByteArray, finish: Boolean, context: String = ""): String = synchronized(lock) {
        var last: Throwable? = null
        repeat(2) {
            try {
                return push(pcm, finish, context)
            } catch (t: Throwable) {
                last = t
                abandon()
            }
        }
        throw last ?: error("asr")
    }

    /** Drops the server session when the live recognizer stops mid-utterance. */
    fun close() = synchronized(lock) { abandon() }

    private fun push(pcm: ByteArray, finish: Boolean, context: String): String {
        if (sessionId == null) {
            if (pcm.isEmpty()) return ""
            open(context)
        }
        val pushed = if (pcm.isEmpty()) "" else postChunk(sessionId!!, pcm)
        if (!finish) return pushed
        val id = sessionId ?: return pushed
        val done = postFinish(id)
        sessionId = null
        return done.ifBlank { pushed }
    }

    private fun open(context: String) {
        val request = Request.Builder()
            .url(streamStart(baseUrl, context))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        sessionId = streamSessionId(execute(client, request, 15))
    }

    private fun postChunk(id: String, pcm: ByteArray): String {
        val request = Request.Builder()
            .url(streamCall(baseUrl, "api/chunk", id))
            .post(pcm16ToFloat32(pcm).toRequestBody(pcmType))
            .build()
        return streamText(execute(client, request, 30))
    }

    private fun postFinish(id: String): String =
        streamText(execute(client, emptyPost("api/finish", id), 30))

    private fun emptyPost(path: String, id: String? = null): Request =
        Request.Builder()
            .url(if (id == null) restUrl(baseUrl, path) else streamCall(baseUrl, path, id))
            .post(ByteArray(0).toRequestBody(null))
            .build()

    private fun abandon() {
        val id = sessionId ?: return
        sessionId = null
        runCatching { postFinish(id) }
    }
}

class IndexTranslator(
    private val client: OkHttpClient,
    private val src: String,
    private val tgt: String,
    private val baseUrl: String,
    private val model: String,
) {
    suspend fun translate(text: String, draft: Boolean): String {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", indexPrompt(src, tgt, text, draft))
                    })
                },
            )
            put("stream", false)
            put("temperature", 0)
            put("max_tokens", 128 + text.length)
            put("cache_prompt", true)
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
        }
        val request = Request.Builder()
            .url(restUrl(baseUrl, "v1/chat/completions"))
            .header("User-Agent", "Index-Translate-Client/1.0")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return awaitContent(client, request, 20)
    }
}

private fun execute(client: OkHttpClient, request: Request, seconds: Long): String {
    val call = client.newCall(request)
    call.timeout().timeout(seconds, TimeUnit.SECONDS)
    return call.execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = raw.take(160).replace('\n', ' ').trim()
            error(if (detail.isEmpty()) "HTTP ${response.code}" else "HTTP ${response.code} $detail")
        }
        raw
    }
}

private fun chatContent(client: OkHttpClient, request: Request, seconds: Long): String =
    messageContent(execute(client, request, seconds))

/** Cancelling the coroutine cancels the HTTP call, so a dropped draft does not keep the model busy. */
private suspend fun awaitContent(client: OkHttpClient, request: Request, seconds: Long): String =
    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        call.timeout().timeout(seconds, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) error("HTTP ${it.code}")
                        if (cont.isActive) cont.resume(messageContent(raw))
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                }
            }
        })
    }

internal fun streamCall(baseUrl: String, path: String, sessionId: String): String {
    val id = URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name())
    return restUrl(baseUrl, path) + "?session_id=$id"
}

internal fun streamStart(baseUrl: String, context: String): String {
    val base = restUrl(baseUrl, "api/start")
    val trimmed = context.trim()
    if (trimmed.isEmpty()) return base
    val q = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
    return "$base?context=$q"
}

internal fun streamSessionId(raw: String): String =
    json.parseToJsonElement(raw).jsonObject["session_id"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("no session")

internal fun streamText(raw: String): String =
    json.parseToJsonElement(raw).jsonObject["text"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()

private fun messageContent(raw: String): String =
    json.parseToJsonElement(raw)
        .jsonObject["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()

internal fun indexPrompt(src: String, tgt: String, text: String, draft: Boolean): String {
    val notes = mutableListOf("【注意】这是直播实时字幕，请译成口语化、简洁的字幕")
    if (draft) {
        notes.add(0, "【注意】源文是一句还没说完的话的开头：只翻译已有的部分，不要补全或猜测后文")
    }
    val rules = notes.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n")
    return "请将以下${zhName(src)}文本翻译成${zhName(tgt)}，并且严格遵循所有约束要求。\n\n【源文】\n$text\n\n【约束要求】\n$rules\n只输出译文，不要有任何额外说明。"
}

internal fun zhName(code: String): String = when (code) {
    "en" -> "英语"
    "ja" -> "日语"
    "ko" -> "韩语"
    "yue" -> "粤语"
    "zh", "zh-Hans", "zh-Hant" -> "中文"
    else -> code
}
