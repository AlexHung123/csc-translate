package com.csc.translate.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class SimulInferApi(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun health(baseUrl: String): Health = get(baseUrl, "health")

    suspend fun config(baseUrl: String): ServiceConfig = get(baseUrl, "config")

    suspend fun submitFile(
        baseUrl: String,
        file: File,
        filename: String,
        mime: String,
        direction: String,
        responseMode: String,
        vad: Boolean,
    ): SubmitResponse = withContext(Dispatchers.IO) {
        val media = mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, file.asRequestBody(media))
            .addFormDataPart("direction", direction)
            .addFormDataPart("response_mode", responseMode)
            .addFormDataPart("vad", vad.toString())
            .addFormDataPart("tts", "false")
            .build()
        val request = Request.Builder().url(restUrl(baseUrl, "translate/submit")).post(body).build()
        execute(request, 10, TimeUnit.MINUTES)
    }

    suspend fun pollTask(baseUrl: String, taskId: String, cursor: Int): TaskPoll =
        withContext(Dispatchers.IO) {
            val path = "translate/task/${java.net.URLEncoder.encode(taskId, Charsets.UTF_8.name())}?cursor=$cursor"
            val request = Request.Builder().url(restUrl(baseUrl, path)).get().build()
            execute(request, 20, TimeUnit.SECONDS)
        }

    suspend fun cancelTask(baseUrl: String, taskId: String) = withContext(Dispatchers.IO) {
        val path = "translate/task/${java.net.URLEncoder.encode(taskId, Charsets.UTF_8.name())}"
        val request = Request.Builder().url(restUrl(baseUrl, path)).delete().build()
        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        runCatching { call.execute().close() }
    }

    private suspend inline fun <reified T> get(baseUrl: String, path: String): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(restUrl(baseUrl, path)).get().build()
            execute(request, 12, TimeUnit.SECONDS)
        }

    private inline fun <reified T> execute(
        request: Request,
        timeout: Long,
        unit: TimeUnit,
    ): T {
        val call = client.newCall(request)
        call.timeout().timeout(timeout, unit)
        return call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            json.decodeFromString<T>(body)
        }
    }
}
