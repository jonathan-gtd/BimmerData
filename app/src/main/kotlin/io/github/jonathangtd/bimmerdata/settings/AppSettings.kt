/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import io.github.jonathangtd.bimmerdata.api.ApiTokens
import io.github.jonathangtd.bimmerdata.api.Scope
import io.github.jonathangtd.bimmerdata.live.LiveMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** User settings of the app (Settings screen). */
data class Settings(
    /** Mode used when connecting without choosing (API `POST /connect`, future quick-connect). */
    val defaultMode: LiveMode = LiveMode.Usb,
    /** Connect over USB as soon as the K+DCAN cable is plugged in (on by default). */
    val autoConnectOnCable: Boolean = true,
    val api: ApiConfig = ApiConfig(),
    /** Mode of the last connection the user made; null until the first one (the Connect pill then asks). */
    val lastMode: LiveMode? = null,
    val recording: RecordingConfig = RecordingConfig(),
    val telegraf: TelegrafSettings = TelegrafSettings(),
)

data class ApiConfig(
    /** Off by default: nothing listens on the network until the user enables it. */
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val token: String = "",
    val scopes: Set<Scope> = setOf(Scope.Read, Scope.Layout),
) {
    companion object {
        const val DEFAULT_PORT = 8765
        val PORT_RANGE = 1024..65535

        /** Scopes the user can grant today (diag comes with the fault memory feature). */
        val GRANTABLE = listOf(Scope.Read, Scope.Layout, Scope.Config)
    }
}

/**
 * Settings stored in app-private SharedPreferences (not backed up: allowBackup is false, so the API
 * token never leaves the phone). Process-wide; observe [state].
 */
class AppSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun load(): Settings {
        var token = prefs.getString(K_TOKEN, null).orEmpty()
        if (token.isEmpty()) {
            token = ApiTokens.generate()
            prefs.edit { putString(K_TOKEN, token) }
        }
        return Settings(
            defaultMode = runCatching { LiveMode.valueOf(prefs.getString(K_MODE, null) ?: "") }.getOrDefault(LiveMode.Usb),
            // new key: the first versions saved "false" as their default under K_AUTO_OLD
            autoConnectOnCable = prefs.getBoolean(K_AUTO, true),
            api = ApiConfig(
                enabled = prefs.getBoolean(K_API_ON, false),
                port = prefs.getInt(K_PORT, ApiConfig.DEFAULT_PORT).takeIf { it in ApiConfig.PORT_RANGE } ?: ApiConfig.DEFAULT_PORT,
                token = token,
                scopes = prefs.getStringSet(K_SCOPES, null)?.mapNotNull { Scope.fromId(it) }?.filter { it in ApiConfig.GRANTABLE }?.toSet()
                    ?: ApiConfig().scopes,
            ),
            lastMode = prefs.getString(K_LAST_MODE, null)?.let { m -> LiveMode.entries.firstOrNull { it.name == m } },
            recording = loadRecording(prefs),
            telegraf = loadTelegraf(prefs),
        )
    }

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        _state.update(transform)
        val s = _state.value
        prefs.edit {
            putString(K_MODE, s.defaultMode.name)
            putBoolean(K_AUTO, s.autoConnectOnCable)
            putBoolean(K_API_ON, s.api.enabled)
            putInt(K_PORT, s.api.port)
            putString(K_TOKEN, s.api.token)
            putStringSet(K_SCOPES, s.api.scopes.filter { it in ApiConfig.GRANTABLE }.map { it.id }.toSet())
            if (s.lastMode != null) putString(K_LAST_MODE, s.lastMode.name) else remove(K_LAST_MODE)
            remove(K_AUTO_OLD)
            saveExtras(s)
        }
    }

    fun updateApi(transform: (ApiConfig) -> ApiConfig) = update { it.copy(api = transform(it.api)) }

    fun updateRecording(transform: (RecordingConfig) -> RecordingConfig) = update { it.copy(recording = transform(it.recording)) }

    fun updateTelegraf(transform: (TelegrafSettings) -> TelegrafSettings) = update { it.copy(telegraf = transform(it.telegraf)) }

    /** New random token: every client using the old one gets 401 from now on. */
    fun regenerateToken() = updateApi { it.copy(token = ApiTokens.generate()) }

    companion object {
        private const val K_MODE = "default_mode"
        private const val K_AUTO_OLD = "auto_connect_cable"
        private const val K_AUTO = "auto_connect_cable_v2"
        private const val K_LAST_MODE = "last_connect_mode"
        private const val K_API_ON = "api_enabled"
        private const val K_PORT = "api_port"
        private const val K_TOKEN = "api_token"
        private const val K_SCOPES = "api_scopes"

        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context).also { instance = it } }
    }
}
