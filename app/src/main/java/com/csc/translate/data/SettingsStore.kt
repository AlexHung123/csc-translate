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
    val pipeline: Pipeline = Pipeline.Simul,
    val baseUrl: String = DEFAULT_BASE_URL,
    val tomoruUrl: String = "",
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

class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val flow: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            pipeline = Pipeline.from(prefs[KEY_PIPELINE]),
            baseUrl = prefs[KEY_URL] ?: DEFAULT_BASE_URL,
            tomoruUrl = prefs[KEY_TOMORU_URL] ?: "",
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
        val KEY_DIRECTION = stringPreferencesKey("direction")
        val KEY_MODE = stringPreferencesKey("response_mode")
        val KEY_VAD = booleanPreferencesKey("vad")
        val KEY_DISPLAY = stringPreferencesKey("display_mode")
        val KEY_FONT = stringPreferencesKey("type_scale")
        val KEY_HEAR = stringPreferencesKey("hear_source")
    }
}
