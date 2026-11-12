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
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val json = Json { ignoreUnknownKeys = true }
private val jsonType = "application/json".toMediaType()

/** This vLLM keeps a system message and drops the assistant prefill llama-server uses. */
private const val CANTONESE = "language Cantonese"

fun probeAsr(client: OkHttpClient, baseUrl: String) {
    val request = Request.Builder().url(restUrl(baseUrl, "v1/models")).get().build()
    execute(client, request, 8)
}

class QwenAsr(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    fun transcribe(pcm: ByteArray): String {
        val audio = buildJsonObject {
            put("type", "input_audio")
            put(
                "input_audio",
                buildJsonObject {
                    put("data", Base64.getEncoder().encodeToString(wavBytes(pcm)))
                    put("format", "wav")
                },
            )
        }
        val body = buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", CANTONESE)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray { add(audio) })
                    })
                },
            )
            put("temperature", 0)
            put("max_tokens", 512)
            put("cache_prompt", false)
        }
        val request = Request.Builder()
            .url(restUrl(baseUrl, "v1/chat/completions"))
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return asrAnswer(chatContent(client, request, 30))
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
        if (!response.isSuccessful) error("HTTP ${response.code}")
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
