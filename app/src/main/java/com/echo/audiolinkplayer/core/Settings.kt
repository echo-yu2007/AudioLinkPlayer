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
     * The UA the in-app browser used when the cookies were captured. Passing the
     * same UA to yt-dlp is what makes those cookies actually work.
     */
    fun userAgent(context: Context): String? = p(context).getString("userAgent", null)

    fun setUserAgent(context: Context, ua: String?) {
        p(context).edit().putString("userAgent", ua).apply()
    }
}
