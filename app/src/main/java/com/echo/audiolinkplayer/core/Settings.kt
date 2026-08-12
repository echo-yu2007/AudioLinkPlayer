package com.echo.audiolinkplayer.core

import android.content.Context
import android.content.SharedPreferences

object Settings {

    private const val FILE = "alp_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(context: Context): SharedPreferences {
        if (!::prefs.isInitialized) init(context)
        return prefs
    }

    /** Default play mode for newly started tracks. */
    var playMode: PlayMode
        get() = runCatching { PlayMode.valueOf(prefs.getString("playMode", null) ?: "") }
            .getOrDefault(PlayMode.AUDIO_ONLY)
        set(v) { prefs.edit().putString("playMode", v.name).apply() }

    var quality: QualityCap
        get() = QualityCap.fromName(prefs.getString("quality", null))
        set(v) { prefs.edit().putString("quality", v.name).apply() }

    var speed: Float
        get() = prefs.getFloat("speed", 1.0f)
        set(v) { prefs.edit().putFloat("speed", v).apply() }

    /** Repeat mode as defined by Player.REPEAT_MODE_*. */
    var repeatMode: Int
        get() = prefs.getInt("repeatMode", 0)
        set(v) { prefs.edit().putInt("repeatMode", v).apply() }

    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(v) { prefs.edit().putBoolean("shuffle", v).apply() }

    /** Disk cache ceiling in MB. 0 disables on-disk caching entirely. */
    var cacheMb: Int
        get() = prefs.getInt("cacheMb", 256)
        set(v) { prefs.edit().putInt("cacheMb", v).apply() }

    /** Auto-refresh yt-dlp on launch (at most once a day). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) { prefs.edit().putBoolean("autoUpdate", v).apply() }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }

    /**
     * The UA the in-app browser used when the cookies were captured.
     *
     * Off by default: yt-dlp ships a UA that its extractors are actually tested
     * against, and forcing a mobile WebView UA onto them is a good way to earn a
     * 403 from a site's bot filter. Only worth turning on when a site's cookies
     * are bound to the exact browser that created them.
     */
    fun userAgent(context: Context): String? =
        if (p(context).getBoolean("useWebViewUa", false))
            p(context).getString("userAgent", null) else null

    fun setUserAgent(context: Context, ua: String?) {
        p(context).edit().putString("userAgent", ua).apply()
    }

    var useWebViewUa: Boolean
        get() = prefs.getBoolean("useWebViewUa", false)
        set(v) { prefs.edit().putBoolean("useWebViewUa", v).apply() }

    /** Proxy for both yt-dlp and the media stream, e.g. http://127.0.0.1:7890. */
    fun proxy(context: Context): String? =
        p(context).getString("proxy", null)?.takeIf { it.isNotBlank() }

    var proxySpec: String
        get() = prefs.getString("proxy", "") ?: ""
        set(v) { prefs.edit().putString("proxy", v.trim()).apply() }

    /** Optional direct URL to a yt-dlp build, tried before the built-in mirrors. */
    fun customEngineUrl(context: Context): String? =
        p(context).getString("engineUrl", null)?.takeIf { it.isNotBlank() }

    var customEngineUrlValue: String
        get() = prefs.getString("engineUrl", "") ?: ""
        set(v) { prefs.edit().putString("engineUrl", v.trim()).apply() }

    /**
     * Retry a failed extraction inside the system WebView. Needed for sites that
     * fingerprint the TLS handshake and reject yt-dlp no matter what headers it sends.
     */
    fun webFallback(context: Context): Boolean =
        p(context).getBoolean("webFallback", true)

    var webFallbackEnabled: Boolean
        get() = prefs.getBoolean("webFallback", true)
        set(v) { prefs.edit().putBoolean("webFallback", v).apply() }

    var nightlyEngine: Boolean
        get() = prefs.getBoolean("nightlyEngine", false)
        set(v) { prefs.edit().putBoolean("nightlyEngine", v).apply() }
}
