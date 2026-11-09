package com.csc.translate

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.csc.translate.data.DisplayMode
import com.csc.translate.data.HearSource
import com.csc.translate.data.Pipeline
import com.csc.translate.data.TypeScale
import com.csc.translate.data.UserSettings
import com.csc.translate.data.db.SessionEntity
import com.csc.translate.net.Health
import com.csc.translate.net.ServiceConfig
import com.csc.translate.pipeline.probeAsr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.csc.translate.session.LiveUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as CscApp).container

    val settings: StateFlow<UserSettings> = container.settings.flow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(),
    )

    val live: StateFlow<LiveUiState> = container.sessions.state
    val messages = container.sessions.messages

    val history: StateFlow<List<SessionEntity>> = container.db.sessionDao().observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _health = MutableStateFlow<Health?>(null)
    val health: StateFlow<Health?> = _health

    private val _config = MutableStateFlow<ServiceConfig?>(null)
    val config: StateFlow<ServiceConfig?> = _config

    private val _healthError = MutableStateFlow<String?>(null)
    val healthError: StateFlow<String?> = _healthError

    init {
        viewModelScope.launch {
            container.settings.ensureMeetingId()
            container.sessions.publishPasscode()
        }
        viewModelScope.launch {
            settings.map { it.activeUrl() }.distinctUntilChanged().collect {
                refreshConnection()
            }
        }
    }

    fun startSession() = container.sessions.start()
    fun startFile(uri: Uri) = container.sessions.startFile(uri)
    fun stopSession() = container.sessions.stop()
    fun togglePause() = container.sessions.togglePause()

    fun toggleDirection() {
        if (live.value.inProgress) return
        viewModelScope.launch { container.settings.toggleDirection() }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch { container.settings.setActiveUrl(url) }
    }

    fun setRelayUrl(url: String) {
        viewModelScope.launch {
            container.settings.setRelayUrl(url)
            container.sessions.publishPasscode()
        }
    }

    fun resetMeetingId() {
        if (live.value.inProgress) return
        viewModelScope.launch {
            container.settings.resetMeetingId()
            container.sessions.publishPasscode()
        }
    }

    fun setPasscode(code: String) {
        if (live.value.inProgress) return
        viewModelScope.launch {
            container.settings.setPasscode(code)
            container.sessions.publishPasscode()
        }
    }

    fun selectPipeline(pipeline: Pipeline, pendingUrl: String) {
        viewModelScope.launch {
            container.settings.setActiveUrl(pendingUrl)
            container.settings.setPipeline(pipeline)
        }
    }

    fun setResponseMode(mode: String) {
        viewModelScope.launch { container.settings.setResponseMode(mode) }
    }

    fun setVad(enabled: Boolean) {
        viewModelScope.launch { container.settings.setVad(enabled) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        viewModelScope.launch { container.settings.setDisplayMode(mode) }
    }

    fun setTypeScale(scale: TypeScale) {
        viewModelScope.launch { container.settings.setTypeScale(scale) }
    }

    fun setHearSource(source: HearSource) {
        if (live.value.inProgress) return
        viewModelScope.launch { container.settings.setHearSource(source) }
    }

    fun deleteSessions(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { container.db.sessionDao().delete(ids) }
    }

    fun session(id: String): SessionEntity? = history.value.firstOrNull { it.id == id }

    fun refreshConnection() {
        viewModelScope.launch {
            val user = container.settings.current()
            val url = user.activeUrl()
            if (url.isBlank()) {
                _health.value = null
                _config.value = null
                _healthError.value = null
                return@launch
            }
            if (user.pipeline == Pipeline.Tomoru) {
                val up = runCatching { withContext(Dispatchers.IO) { probeAsr(container.http, url) } }
                if (up.isSuccess) {
                    _health.value = Health(status = "ok")
                    _healthError.value = null
                } else {
                    _health.value = null
                    _healthError.value = url
                }
                _config.value = null
                return@launch
            }
            val healthResult = runCatching { container.api.health(url) }
            healthResult
                .onSuccess {
                    _health.value = it
                    _healthError.value = null
                }
                .onFailure {
                    _health.value = null
                    _healthError.value = url
                }
            runCatching { container.api.config(url) }
                .onSuccess { _config.value = it }
        }
    }
}
