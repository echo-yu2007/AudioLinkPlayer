package com.echo.audiolinkplayer

import android.app.Application
import com.echo.audiolinkplayer.core.EngineUpdater
import com.echo.audiolinkplayer.core.Extractor
import com.echo.audiolinkplayer.core.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)

        // Unpacking the python runtime takes a few seconds on first launch; do it
        // eagerly so the first paste does not sit there spinning.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Extractor.ensureInit(this@App) }
            val version = runCatching { Extractor.refreshVersion(this@App) }.getOrNull()

            // The yt-dlp baked into the APK goes stale fast and stale means HTTP 403
            // on the big sites, so refresh whenever it is old — not just once a day.
            val aDay = 24 * 60 * 60 * 1000L
            val due = System.currentTimeMillis() - Settings.lastUpdateCheck > aDay
            if (Settings.autoUpdate && (due || EngineUpdater.isStale(version))) {
                runCatching { Extractor.updateEngine(this@App) }
                Settings.lastUpdateCheck = System.currentTimeMillis()
            }
        }
    }
}
