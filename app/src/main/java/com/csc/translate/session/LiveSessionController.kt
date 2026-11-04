package com.csc.translate.session

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.csc.translate.R
import com.csc.translate.audio.PcmRecorder
import com.csc.translate.data.SettingsStore
import com.csc.translate.data.db.SessionDao
import com.csc.translate.data.db.SessionEntity
import com.csc.translate.net.Handshake
import com.csc.translate.net.Health
import com.csc.translate.net.ServerEvent
import com.csc.translate.net.SimulInferApi
import com.csc.translate.net.TranslateSocket
import com.csc.translate.service.MicCaptureService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase { Idle, Connecting, Live, Finishing }

data class LiveUiState(
    val phase: Phase = Phase.Idle,
    val paused: Boolean = false,
    val fromFile: Boolean = false,
    val direction: String = "en-zh",
    val sourceText: String = "",
    val targetText: String = "",
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
) {
    val inProgress: Boolean get() = phase != Phase.Idle
}

class LiveSessionController(
    private val app: Application,
    private val settings: SettingsStore,
    private val dao: SessionDao,
    private val api: SimulInferApi,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var socket: TranslateSocket? = null
    private var recorder: PcmRecorder? = null
    private var timerJob: Job? = null
    private var handshakeTimeout: Job? = null
    private var runJob: Job? = null
    private val paused = AtomicBoolean(false)
    private var silenceFrame: ByteArray = ByteArray(0)
    private var sessionId: String = ""
    private var startedAt: Long = 0L
    private var pausedAt: Long? = null
    private var pausedFor: Long = 0L
    private var transcript = Transcript()
    private var ready = false
    private var finishing = false
    private val sessionOpen = AtomicBoolean(false)
    private var fromFile = false
    private var uploadTaskId: String? = null
    private var tempUpload: File? = null

    val inProgress: Boolean get() = _state.value.inProgress

    fun start() {
        if (_state.value.phase != Phase.Idle) return
        runJob = scope.launch { startInternal() }
    }

    fun startFile(uri: Uri) {
        if (_state.value.phase != Phase.Idle) return
        runJob = scope.launch { startFileInternal(uri) }
    }

    fun togglePause() {
        val current = _state.value
        if (current.phase != Phase.Live || current.fromFile) return
        if (current.paused) resume() else pause()
    }

    fun pause() {
        val current = _state.value
        if (current.phase != Phase.Live || current.paused || current.fromFile) return
        paused.set(true)
        pausedAt = System.currentTimeMillis()
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        val current = _state.value
        if (current.phase != Phase.Live || !current.paused) return
        val now = System.currentTimeMillis()
        pausedFor += now - (pausedAt ?: now)
        pausedAt = null
        paused.set(false)
        _state.update { it.copy(paused = false, elapsedMs = nowElapsed()) }
    }

    fun stop() {
        if (_state.value.phase == Phase.Idle) return
        scope.launch { finish(userStop = true) }
    }

    fun abort(message: String? = null) {
        scope.launch {
            if (message != null) _messages.emit(message)
            teardown(save = transcript.isEmpty.not())
        }
    }

    private fun resetSession(fileSession: Boolean) {
        fromFile = fileSession
        uploadTaskId = null
        transcript = Transcript()
        ready = false
        finishing = false
        paused.set(false)
        pausedAt = null
        pausedFor = 0L
        sessionId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
    }

    private suspend fun startInternal() {
        val user = settings.current()
        resetSession(fileSession = false)
        _state.value = LiveUiState(
            phase = Phase.Connecting,
            direction = user.direction,
            startedAt = startedAt,
        )

        val health: Health = runCatching { api.health(user.baseUrl) }.getOrElse {
            Log.w(TAG, "health failed", it)
            failToIdle(app.getString(R.string.connect_fail))
            return
        }
        if (sessionId.isBlank()) return
        if (health.activeSessions >= health.maxSessions) {
            failToIdle(app.getString(R.string.busy))
            return
        }

        val rate = PcmRecorder.pickSampleRate() ?: run {
            failToIdle(app.getString(R.string.mic_fail))
            return
        }
        silenceFrame = ByteArray(rate / 10 * 2)
        sessionOpen.set(true)
        MicCaptureService.start(app)

        val handshake = Handshake(
            direction = user.direction,
            sampleRate = rate,
            responseMode = user.responseMode,
            vad = user.vad,
        )
        socket = TranslateSocket(
            client = http,
            json = json,
            baseUrl = user.baseUrl,
            handshake = handshake,
            onEvent = { event -> scope.launch { handleEvent(event) } },
            onDisconnected = { reason ->
                scope.launch {
                    if (!sessionOpen.get()) return@launch
                    if (finishing) {
                        persist()
                        teardown(save = false)
                    } else {
                        _messages.emit(reason ?: app.getString(R.string.socket_fail))
                        teardown(save = transcript.isEmpty.not())
                    }
                }
            },
        )
        handshakeTimeout = scope.launch {
            delay(15_000)
            if (!ready && _state.value.phase == Phase.Connecting) {
                _messages.emit(app.getString(R.string.handshake_timeout))
                teardown(save = false)
            }
        }
    }

    private suspend fun startFileInternal(uri: Uri) {
        val user = settings.current()
        resetSession(fileSession = true)
        val myId = sessionId
        _state.value = LiveUiState(
            phase = Phase.Connecting,
            fromFile = true,
            direction = user.direction,
            startedAt = startedAt,
        )

        val health: Health = runCatching { api.health(user.baseUrl) }.getOrElse {
            Log.w(TAG, "health failed", it)
            if (sessionId != myId) return
            failToIdle(app.getString(R.string.connect_fail))
            return
        }
        if (sessionId != myId) return
        if (health.activeSessions >= health.maxSessions) {
            failToIdle(app.getString(R.string.busy))
            return
        }

        val copied = runCatching { copyUpload(uri) }.getOrElse { error ->
            Log.w(TAG, "copy upload", error)
            if (sessionId != myId) return
            val msg = if (error.message == "too_large") {
                app.getString(R.string.file_too_large)
            } else {
                app.getString(R.string.file_read_fail)
            }
            failToIdle(msg)
            return
        }
        if (sessionId != myId) {
            copied.file.delete()
            return
        }
        tempUpload = copied.file

        val mime = mimeFromFileName(copied.filename, app.contentResolver.getType(uri))
        sessionOpen.set(true)
        startTimer()

        val submit = runCatching {
            api.submitFile(
                baseUrl = user.baseUrl,
                file = copied.file,
                filename = copied.filename,
                mime = mime,
                direction = user.direction,
                responseMode = user.responseMode,
                vad = user.vad,
            )
        }
        if (sessionId != myId) {
            submit.getOrNull()?.let { runCatching { api.cancelTask(user.baseUrl, it.taskId) } }
            copied.file.delete()
            return
        }
        val body = submit.getOrElse {
            Log.w(TAG, "submit failed", it)
            val msg = if (it.message?.contains("HTTP 413") == true) {
                app.getString(R.string.file_too_large)
            } else {
                app.getString(R.string.file_submit_fail)
            }
            failToIdle(msg)
            return
        }
        uploadTaskId = body.taskId
        _state.update { it.copy(phase = Phase.Live, fromFile = true) }
        pollUntilDone(user.baseUrl, body.taskId)
    }

    private data class CopiedUpload(val file: File, val filename: String)

    private suspend fun copyUpload(uri: Uri): CopiedUpload = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        var name = "audio"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.let { name = it }
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
        }
        if (size > MAX_UPLOAD_BYTES) error("too_large")
        val filename = safeUploadFileName(name)
        val dest = File(app.cacheDir, "upload-${UUID.randomUUID()}-$filename")
        try {
            val input = resolver.openInputStream(uri) ?: error("read_fail")
            input.use { stream ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > MAX_UPLOAD_BYTES) error("too_large")
                        output.write(buf, 0, n)
                    }
                    if (written == 0L) error("empty")
                }
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
        CopiedUpload(dest, filename)
    }

    private suspend fun pollUntilDone(baseUrl: String, taskId: String) {
        var cursor = 0
        try {
            while (sessionOpen.get()) {
                val poll = api.pollTask(baseUrl, taskId, cursor)
                for (event in poll.events) {
                    if (!sessionOpen.get()) return
                    handleEvent(event)
                }
                if (!sessionOpen.get()) return
                cursor = poll.nextCursor
                if (poll.done && cursor >= poll.totalEvents) {
                    if (sessionOpen.get()) {
                        uploadTaskId = null
                        persist()
                        teardown(save = false)
                    }
                    return
                }
                if (cursor < poll.totalEvents) continue
                delay(500)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "poll failed", t)
            if (sessionOpen.get()) {
                _messages.emit(app.getString(R.string.socket_fail))
                teardown(save = transcript.isEmpty.not())
            }
        }
    }

    private suspend fun handleEvent(event: ServerEvent) {
        if (!sessionOpen.get()) return
        when (event.event) {
            "ready" -> {
                ready = true
                handshakeTimeout?.cancel()
                if (fromFile) {
                    startTimer()
                } else {
                    startCapture()
                }
                _state.update {
                    it.copy(
                        phase = Phase.Live,
                        fromFile = fromFile,
                        direction = event.direction ?: it.direction,
                    )
                }
            }
            "chunk", "flush" -> {
                transcript = transcript.applyEvent(event)
                publishTranscript()
            }
            "done" -> {
                transcript = transcript.applyEvent(event)
                publishTranscript()
                finishing = true
                uploadTaskId = null
                persist()
                teardown(save = false)
            }
            "error" -> {
                _messages.emit(event.detail ?: app.getString(R.string.socket_fail))
                teardown(save = transcript.isEmpty.not())
            }
            else -> Unit
        }
    }

    private fun startCapture() {
        val rec = PcmRecorder(
            onFrame = { frame ->
                val payload = if (paused.get()) silenceFrame else frame
                socket?.sendPcm(payload)
            },
            onError = { message ->
                scope.launch {
                    _messages.emit(message)
                    teardown(save = transcript.isEmpty.not())
                }
            },
        )
        recorder = rec
        try {
            rec.start()
        } catch (t: Throwable) {
            Log.w(TAG, "recorder start", t)
            scope.launch {
                _messages.emit(app.getString(R.string.mic_fail))
                teardown(save = false)
            }
            return
        }
        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_state.value.inProgress) {
                _state.update { it.copy(elapsedMs = nowElapsed()) }
                delay(200)
            }
        }
    }

    private suspend fun finish(userStop: Boolean) {
        if (finishing) return
        finishing = true
        handshakeTimeout?.cancel()
        _state.update { it.copy(phase = Phase.Finishing, paused = false) }
        paused.set(false)
        recorder?.stop()
        recorder = null
        if (fromFile) {
            persist()
            teardown(save = false)
            return
        }
        val sock = socket
        if (ready && sock != null) {
            sock.sendEof()
            val done = withTimeoutOrNull(45_000) {
                while (_state.value.phase != Phase.Idle) delay(100)
            }
            if (done == null) {
                persist()
                teardown(save = false)
            }
        } else {
            teardown(save = false)
        }
        if (userStop) Unit
    }

    private suspend fun persist() {
        val elapsed = nowElapsed()
        val entity = SessionEntity(
            id = sessionId.ifBlank { UUID.randomUUID().toString() },
            direction = _state.value.direction,
            title = ChineseDisplay.toTraditional(
                TitleFormatter.title(transcript.sourceRaw, transcript.targetRaw),
            ),
            sourceText = ChineseDisplay.toTraditional(transcript.sourceRaw),
            targetText = ChineseDisplay.toTraditional(transcript.targetRaw),
            startedAt = startedAt,
            durationMs = elapsed,
        )
        runCatching { dao.upsert(entity) }
            .onFailure { Log.w(TAG, "persist failed", it) }
    }

    private suspend fun teardown(save: Boolean) {
        withContext(NonCancellable) {
            sessionOpen.set(false)
            handshakeTimeout?.cancel()
            timerJob?.cancel()
            val job = runJob
            if (job != null && job != coroutineContext[Job]) {
                job.cancel()
            }
            recorder?.stop()
            recorder = null
            socket?.close()
            socket = null
            val taskId = uploadTaskId
            val baseUrl = runCatching { settings.current().baseUrl }.getOrNull()
            uploadTaskId = null
            if (taskId != null && baseUrl != null) {
                runCatching { api.cancelTask(baseUrl, taskId) }
            }
            tempUpload?.delete()
            tempUpload = null
            if (save) persist()
            MicCaptureService.stop(app)
            ready = false
            finishing = false
            fromFile = false
            sessionId = ""
            paused.set(false)
            pausedAt = null
            pausedFor = 0L
            transcript = Transcript()
            _state.value = LiveUiState(direction = _state.value.direction)
        }
    }

    private suspend fun failToIdle(message: String) {
        _messages.emit(message)
        teardown(save = false)
    }

    private fun publishTranscript() {
        _state.update {
            it.copy(
                sourceText = sourceForDisplay(transcript.sourceRaw, it.direction),
                targetText = targetForDisplay(transcript.targetRaw),
            )
        }
    }

    private fun nowElapsed(): Long {
        val end = pausedAt ?: System.currentTimeMillis()
        return (end - startedAt - pausedFor).coerceAtLeast(0)
    }

    private companion object {
        const val TAG = "CscTranslate"
    }
}
