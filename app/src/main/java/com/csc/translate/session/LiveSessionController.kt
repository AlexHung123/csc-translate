package com.csc.translate.session

import android.app.Application
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.csc.translate.R
import com.csc.translate.audio.AudioCapture
import com.csc.translate.audio.PcmRecorder
import com.csc.translate.audio.PlaybackRecorder
import com.csc.translate.audio.SystemAudio
import com.csc.translate.data.HearSource
import com.csc.translate.data.Pipeline
import com.csc.translate.data.SettingsStore
import com.csc.translate.data.UserSettings
import com.csc.translate.data.db.SessionDao
import com.csc.translate.data.db.SessionEntity
import com.csc.translate.net.Handshake
import com.csc.translate.pipeline.IndexTranslator
import com.csc.translate.pipeline.QwenAsr
import com.csc.translate.pipeline.Silero
import com.csc.translate.pipeline.StretchSession
import com.csc.translate.pipeline.mono
import com.csc.translate.pipeline.probeAsr
import com.csc.translate.pipeline.readWav
import com.csc.translate.pipeline.resample16
import com.csc.translate.net.Health
import com.csc.translate.net.RelayClient
import com.csc.translate.net.RelayPicture
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
import java.util.Locale
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
    val watchUrl: String = "",
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
    private var asr: StretchSession? = null
    private var recorder: AudioCapture? = null
    private var hearSystem = false
    private var timerJob: Job? = null
    private var handshakeTimeout: Job? = null
    private var runJob: Job? = null
    private val paused = AtomicBoolean(false)
    private var silenceFrame: ByteArray = ByteArray(0)
    private var sessionId: String = ""
    private var sessionUrl: String = ""
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
    private val relayShare = RelayShare(RelayClient(http, json), scope)

    init {
        relayShare.onWatchUrl = { url ->
            _state.update { current ->
                if (!current.inProgress) current else current.copy(watchUrl = url)
            }
        }
        relayShare.onOpenFailed = {
            _messages.tryEmit(app.getString(R.string.relay_fail))
        }
        scope.launch {
            settings.flow.collect {
                if (_state.value.inProgress) noteRelay()
            }
        }
    }

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
        noteRelay()
    }

    fun resume() {
        val current = _state.value
        if (current.phase != Phase.Live || !current.paused) return
        val now = System.currentTimeMillis()
        pausedFor += now - (pausedAt ?: now)
        pausedAt = null
        paused.set(false)
        _state.update { it.copy(paused = false, elapsedMs = nowElapsed()) }
        noteRelay()
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
        hearSystem = false
        relayShare.begin()
    }

    private suspend fun startInternal() {
        val user = settings.current()
        resetSession(fileSession = false)
        _state.value = LiveUiState(
            phase = Phase.Connecting,
            direction = user.direction,
            startedAt = startedAt,
        )
        noteRelay()
        if (user.hearSource == HearSource.System && !prepareSystem()) return

        val endpoint = user.activeUrl()
        if (endpoint.isBlank()) {
            failToIdle(app.getString(R.string.connect_fail))
            return
        }
        sessionUrl = endpoint
        if (user.pipeline == Pipeline.Tomoru) {
            startAsr(user.direction, endpoint)
            return
        }
        val health: Health = runCatching { api.health(endpoint) }.getOrElse {
            Log.w(TAG, "health failed", it)
            failToIdle(app.getString(R.string.connect_fail))
            return
        }
        if (sessionId.isBlank()) return
        if (health.activeSessions >= health.maxSessions) {
            failToIdle(app.getString(R.string.busy))
            return
        }

        val rate = inputRate() ?: run {
            failToIdle(app.getString(if (hearSystem) R.string.system_audio_fail else R.string.mic_fail))
            return
        }
        silenceFrame = ByteArray(rate / 10 * 2)
        sessionOpen.set(true)
        if (!hearSystem) MicCaptureService.start(app)

        val handshake = Handshake(
            direction = user.direction,
            sampleRate = rate,
            responseMode = user.responseMode,
            vad = user.vad,
        )
        socket = TranslateSocket(
            client = http,
            json = json,
            baseUrl = endpoint,
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
        noteRelay()

        val endpoint = user.activeUrl()
        if (endpoint.isBlank()) {
            failToIdle(app.getString(R.string.connect_fail))
            return
        }
        sessionUrl = endpoint
        if (user.pipeline == Pipeline.Tomoru) {
            startAsrFile(uri, user.direction, endpoint, myId)
            return
        }
        val health: Health = runCatching { api.health(endpoint) }.getOrElse {
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
                baseUrl = endpoint,
                file = copied.file,
                filename = copied.filename,
                mime = mime,
                direction = user.direction,
                responseMode = user.responseMode,
                vad = user.vad,
            )
        }
        if (sessionId != myId) {
            submit.getOrNull()?.let { runCatching { api.cancelTask(endpoint, it.taskId) } }
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
        pollUntilDone(endpoint, body.taskId)
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
                noteRelay()
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

    private suspend fun startAsr(direction: String, endpoint: String) {
        val up = runCatching { withContext(Dispatchers.IO) { probeAsr(http, endpoint) } }
        if (sessionId.isBlank()) return
        if (up.isFailure) {
            Log.w(TAG, "asr probe failed", up.exceptionOrNull())
            failToIdle(app.getString(R.string.connect_fail))
            return
        }
        val rate = inputRate() ?: run {
            failToIdle(app.getString(if (hearSystem) R.string.system_audio_fail else R.string.mic_fail))
            return
        }
        silenceFrame = ByteArray(rate / 10 * 2)
        val voice = openSilero(sessionId) ?: return
        ready = true
        sessionOpen.set(true)
        if (!hearSystem) MicCaptureService.start(app)
        asr = newAsrSession(direction, endpoint, voice)
        _state.update { it.copy(phase = Phase.Live) }
        noteRelay()
        startCapture()
    }

    private suspend fun startAsrFile(uri: Uri, direction: String, endpoint: String, myId: String) {
        val up = runCatching { withContext(Dispatchers.IO) { probeAsr(http, endpoint) } }
        if (sessionId != myId) return
        if (up.isFailure) {
            Log.w(TAG, "asr probe failed", up.exceptionOrNull())
            failToIdle(app.getString(R.string.connect_fail))
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
        val wav = withContext(Dispatchers.IO) { readWav(copied.file.readBytes()) }
        if (sessionId != myId) return
        if (wav == null) {
            failToIdle(app.getString(R.string.file_read_fail))
            return
        }
        val pcm = resample16(mono(wav.pcm, wav.channels), wav.rate)
        if (sessionId != myId) return
        val voice = openSilero(myId) ?: return
        sessionOpen.set(true)
        ready = true
        startTimer()
        val session = newAsrSession(direction, endpoint, voice)
        asr = session
        _state.update { it.copy(phase = Phase.Live, fromFile = true) }
        noteRelay()
        session.write(pcm)
        runCatching { session.finish() }
        if (!sessionOpen.get()) return
        persist()
        teardown(save = false)
    }

    private suspend fun openSilero(expected: String): Silero? {
        val voice = runCatching {
            withContext(Dispatchers.IO) {
                app.assets.open("silero_vad.onnx").use { Silero(it.readBytes()) }
            }
        }.getOrElse {
            Log.w(TAG, "silero", it)
            if (sessionId == expected) failToIdle(app.getString(R.string.silero_fail))
            return null
        }
        if (sessionId != expected) {
            voice.close()
            return null
        }
        return voice
    }

    private fun newAsrSession(direction: String, endpoint: String, voice: Silero): StretchSession {
        val parts = direction.split("-", limit = 2)
        val src = parts.getOrElse(0) { "zh" }
        val tgt = parts.getOrElse(1) { "en" }
        val recognizer = QwenAsr(http, endpoint)
        val translator = IndexTranslator(http, src, tgt)
        return StretchSession(
            parent = scope,
            srcLang = src,
            tgtLang = tgt,
            decode = { recognizer.transcribe(it) },
            translate = { text, draft -> translator.translate(text, draft) },
            voice = voice,
            onView = { source, target ->
                scope.launch {
                    if (!sessionOpen.get()) return@launch
                    transcript = Transcript(source, target)
                    publishTranscript()
                }
            },
            onError = {
                scope.launch {
                    if (!sessionOpen.get()) return@launch
                    _messages.emit(app.getString(R.string.asr_fail))
                    teardown(save = transcript.isEmpty.not())
                }
            },
        )
    }

    private fun inputRate(): Int? = if (hearSystem) {
        (recorder as? PlaybackRecorder)?.sampleRate?.takeIf { it > 0 }
    } else {
        PcmRecorder.pickSampleRate()
    }

    private suspend fun prepareSystem(): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            failToIdle(app.getString(R.string.system_audio_unavailable))
            return false
        }
        hearSystem = true
        SystemAudio.onRevoked = {
            scope.launch {
                if (_state.value.phase == Phase.Idle && recorder == null) return@launch
                _messages.emit(app.getString(R.string.system_audio_stopped))
                teardown(save = transcript.isEmpty.not())
            }
        }
        val projection = SystemAudio.await()
        if (projection == null) {
            failToIdle(app.getString(R.string.system_audio_fail))
            return false
        }
        val rec = openPlayback(projection)
        if (rec == null) {
            failToIdle(app.getString(R.string.system_audio_fail))
            return false
        }
        recorder = rec
        return true
    }

    @RequiresApi(29)
    private fun openPlayback(projection: MediaProjection): PlaybackRecorder? {
        lateinit var rec: PlaybackRecorder
        rec = PlaybackRecorder(
            projection = projection,
            onFrame = { frame -> deliver(frame, rec.sampleRate) },
            onError = {
                scope.launch {
                    _messages.emit(app.getString(R.string.system_audio_stopped))
                    teardown(save = transcript.isEmpty.not())
                }
            },
        )
        val opened = runCatching { rec.open() }
            .onFailure { Log.w(TAG, "playback open", it) }
            .getOrDefault(false)
        if (!opened) {
            rec.stop()
            return null
        }
        return rec
    }

    private fun deliver(frame: ByteArray, rate: Int) {
        val payload = if (paused.get()) silenceFrame else frame
        val session = asr
        if (session != null) {
            session.write(resample16(payload, rate))
        } else {
            socket?.sendPcm(payload)
        }
    }

    private fun startCapture() {
        if (hearSystem) {
            val rec = recorder as? PlaybackRecorder
            if (rec == null) {
                scope.launch {
                    _messages.emit(app.getString(R.string.system_audio_fail))
                    teardown(save = false)
                }
                return
            }
            try {
                rec.start()
            } catch (t: Throwable) {
                Log.w(TAG, "playback start", t)
                scope.launch {
                    _messages.emit(app.getString(R.string.system_audio_fail))
                    teardown(save = false)
                }
                return
            }
            startTimer()
            return
        }
        lateinit var rec: PcmRecorder
        rec = PcmRecorder(
            onFrame = { frame -> deliver(frame, rec.sampleRate) },
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
        noteRelay()
        paused.set(false)
        recorder?.stop()
        recorder = null
        val session = asr
        if (session != null) {
            runCatching { session.finish() }
            persist()
            teardown(save = false)
            return
        }
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
            closeRelay()
            sessionOpen.set(false)
            handshakeTimeout?.cancel()
            timerJob?.cancel()
            val job = runJob
            if (job != null && job != coroutineContext[Job]) {
                job.cancel()
            }
            SystemAudio.onRevoked = null
            recorder?.stop()
            recorder = null
            SystemAudio.release()
            asr?.stop()
            asr = null
            socket?.close()
            socket = null
            val taskId = uploadTaskId
            val baseUrl = sessionUrl.ifBlank { null }
            sessionUrl = ""
            uploadTaskId = null
            if (taskId != null && baseUrl != null) {
                runCatching { api.cancelTask(baseUrl, taskId) }
            }
            tempUpload?.delete()
            tempUpload = null
            if (save) persist()
            MicCaptureService.stop(app)
            hearSystem = false
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
        noteRelay()
    }

    private fun noteRelay() {
        scope.launch {
            val live = _state.value
            if (!live.inProgress) return@launch
            val user = settings.current()
            val meetingId = settings.ensureMeetingId()
            val base = user.relayUrl.trim().trimEnd('/')
            if (base.isNotBlank()) {
                val watch = "$base/watch/$meetingId"
                _state.update { current ->
                    if (!current.inProgress || current.watchUrl == watch) current
                    else current.copy(watchUrl = watch)
                }
            }
            val latest = _state.value
            if (!latest.inProgress) return@launch
            relayShare.sync(base, meetingId, user.passcode, relayPicture(latest, user, relayStatus(latest)))
        }
    }

    private suspend fun closeRelay() {
        val live = _state.value
        val user = settings.current()
        relayShare.finish(user.relayUrl, user.passcode, relayPicture(live, user, status = ""))
    }

    private fun relayStatus(live: LiveUiState): String = when {
        live.phase == Phase.Connecting && live.fromFile -> app.getString(R.string.uploading_file)
        live.phase == Phase.Connecting -> app.getString(R.string.connecting)
        live.phase == Phase.Finishing -> app.getString(R.string.finishing)
        live.paused -> app.getString(R.string.paused)
        live.fromFile -> app.getString(R.string.translating_file)
        hearSystem -> app.getString(R.string.listening_system)
        else -> app.getString(R.string.listening_en)
    }

    private fun relayPicture(live: LiveUiState, user: UserSettings, status: String) = RelayPicture(
        phase = live.phase.name.lowercase(Locale.ROOT),
        direction = live.direction.ifBlank { user.direction },
        displayMode = user.displayMode.key,
        typeScale = user.typeScale.key,
        sourceText = live.sourceText,
        targetText = live.targetText,
        paused = live.paused,
        status = status,
    )

    private fun nowElapsed(): Long {
        val end = pausedAt ?: System.currentTimeMillis()
        return (end - startedAt - pausedFor).coerceAtLeast(0)
    }

    private companion object {
        const val TAG = "CscTranslate"
    }
}
