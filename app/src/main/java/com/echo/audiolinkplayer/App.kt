package com.echo.audiolinkplayer

import android.app.Application
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

            val aDay = 24 * 60 * 60 * 1000L
            if (Settings.autoUpdate && System.currentTimeMillis() - Settings.lastUpdateCheck > aDay) {
                runCatching { Extractor.updateEngine(this@App) }
                Settings.lastUpdateCheck = System.currentTimeMillis()
            }
        }
    }
}
