package com.csc.translate.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("csc_settings")

enum class Pipeline(val key: String) {
    Simul("simul"),
    Tomoru("tomoru"),
    ;

    companion object {
        fun from(key: String?) = entries.find { it.key == key } ?: Simul
    }
}

/** Settings hides the pipeline picker. Sessions use ASR + Translate until this is true again. */
const val SHOW_PIPELINE_CHOICE = false

enum class HearSource(val key: String) {
    Mic("mic"),
    System("system"),
    ;

    companion object {
        fun from(key: String?) = entries.find { it.key == key } ?: Mic
    }
}

enum class DisplayMode(val key: String) {
    Split("split"),
    Translation("translation"),
    Source("source"),
    ;

    companion object {
        fun from(key: String?) = entries.find { it.key == key } ?: Split
    }
}

enum class TypeScale(val key: String, val sp: Int, val lineHeight: Int) {
    Small("small", 14, 22),
    Medium("medium", 16, 26),
    Large("large", 22, 32),
    ;

    companion object {
        fun from(key: String?) = entries.find { it.key == key } ?: Medium
    }
}

data class UserSettings(
    val pipeline: Pipeline = Pipeline.Tomoru,
    val baseUrl: String = DEFAULT_BASE_URL,
    val tomoruUrl: String = "",
    val relayUrl: String = DEFAULT_RELAY_URL,
    val shareRelay: Boolean = true,
    val meetingId: String = "",
    val passcode: String = "",
    val direction: String = "en-zh",
    val responseMode: String = "standard",
    val vad: Boolean = true,
    val displayMode: DisplayMode = DisplayMode.Split,
    val typeScale: TypeScale = TypeScale.Medium,
    val hearSource: HearSource = HearSource.Mic,
) {
    val sourceIsEnglish: Boolean get() = direction == "en-zh"

    fun activeUrl(): String = when (pipeline) {
        Pipeline.Simul -> baseUrl
        Pipeline.Tomoru -> tomoruUrl
    }.trim().trimEnd('/')
}

const val DEFAULT_BASE_URL = "http://192.168.1.241:8080"
const val DEFAULT_RELAY_URL = "http://192.168.1.58:8091"

class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val flow: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            pipeline = if (SHOW_PIPELINE_CHOICE) Pipeline.from(prefs[KEY_PIPELINE]) else Pipeline.Tomoru,
            baseUrl = prefs[KEY_URL] ?: DEFAULT_BASE_URL,
            tomoruUrl = prefs[KEY_TOMORU_URL] ?: "",
            relayUrl = prefs[KEY_RELAY_URL] ?: DEFAULT_RELAY_URL,
            shareRelay = prefs[KEY_SHARE_RELAY] ?: true,
            meetingId = prefs[KEY_MEETING].orEmpty(),
            passcode = prefs[KEY_PASSCODE].orEmpty(),
            direction = prefs[KEY_DIRECTION] ?: "en-zh",
            responseMode = prefs[KEY_MODE] ?: "standard",
            vad = prefs[KEY_VAD] ?: true,
            displayMode = DisplayMode.from(prefs[KEY_DISPLAY]),
            typeScale = TypeScale.from(prefs[KEY_FONT]),
            hearSource = HearSource.from(prefs[KEY_HEAR]),
        )
    }.distinctUntilChanged()

    suspend fun current(): UserSettings = flow.first()

    suspend fun setPipeline(pipeline: Pipeline) {
        dataStore.edit { it[KEY_PIPELINE] = pipeline.key }
    }

    suspend fun setBaseUrl(url: String) {
        val cleaned = cleanUrl(url) ?: return
        dataStore.edit { it[KEY_URL] = cleaned }
    }

    suspend fun setTomoruUrl(url: String) {
        val cleaned = cleanUrl(url) ?: return
        dataStore.edit { it[KEY_TOMORU_URL] = cleaned }
    }

    suspend fun setRelayUrl(url: String) {
        dataStore.edit { it[KEY_RELAY_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setShareRelay(enabled: Boolean) {
        dataStore.edit { it[KEY_SHARE_RELAY] = enabled }
    }

    suspend fun ensureMeetingId(): String {
        val existing = MeetingIds.canonical(current().meetingId)
        if (existing != null) return existing
        return storeMeetingId(MeetingIds.generate())
    }

    suspend fun resetMeetingId(): String = storeMeetingId(MeetingIds.generate())

    suspend fun setPasscode(raw: String) {
        val cleaned = Passcodes.normalize(raw) ?: return
        dataStore.edit { it[KEY_PASSCODE] = cleaned }
    }

    private suspend fun storeMeetingId(id: String): String {
        dataStore.edit { it[KEY_MEETING] = id }
        return id
    }

    suspend fun setActiveUrl(url: String) {
        when (current().pipeline) {
            Pipeline.Simul -> setBaseUrl(url)
            Pipeline.Tomoru -> setTomoruUrl(url)
        }
    }

    suspend fun setDirection(direction: String) {
        dataStore.edit { it[KEY_DIRECTION] = direction }
    }

    suspend fun toggleDirection() {
        dataStore.edit { prefs ->
            val current = prefs[KEY_DIRECTION] ?: "en-zh"
            prefs[KEY_DIRECTION] = if (current == "en-zh") "zh-en" else "en-zh"
        }
    }

    suspend fun setResponseMode(mode: String) {
        dataStore.edit { it[KEY_MODE] = mode }
    }

    suspend fun setVad(enabled: Boolean) {
        dataStore.edit { it[KEY_VAD] = enabled }
    }

    suspend fun setDisplayMode(mode: DisplayMode) {
        dataStore.edit { it[KEY_DISPLAY] = mode.key }
    }

    suspend fun setTypeScale(scale: TypeScale) {
        dataStore.edit { it[KEY_FONT] = scale.key }
    }

    suspend fun setHearSource(source: HearSource) {
        dataStore.edit { it[KEY_HEAR] = source.key }
    }

    private companion object {
        fun cleanUrl(url: String): String? {
            val cleaned = url.trim().trimEnd('/')
            if (cleaned.isBlank()) return null
            return cleaned
        }

        val KEY_PIPELINE = stringPreferencesKey("pipeline")
        val KEY_URL = stringPreferencesKey("base_url")
        val KEY_TOMORU_URL = stringPreferencesKey("tomoru_url")
        val KEY_RELAY_URL = stringPreferencesKey("relay_url")
        val KEY_SHARE_RELAY = booleanPreferencesKey("share_relay")
        val KEY_MEETING = stringPreferencesKey("meeting_id")
        val KEY_PASSCODE = stringPreferencesKey("passcode")
        val KEY_DIRECTION = stringPreferencesKey("direction")
        val KEY_MODE = stringPreferencesKey("response_mode")
        val KEY_VAD = booleanPreferencesKey("vad")
        val KEY_DISPLAY = stringPreferencesKey("display_mode")
        val KEY_FONT = stringPreferencesKey("type_scale")
        val KEY_HEAR = stringPreferencesKey("hear_source")
    }
}
