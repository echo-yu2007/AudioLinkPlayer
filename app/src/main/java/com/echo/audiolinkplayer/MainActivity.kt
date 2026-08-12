package com.echo.audiolinkplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.echo.audiolinkplayer.ui.HomeScreen
import com.echo.audiolinkplayer.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val sharedLink = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        handleIntent(intent)

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(
                        primary = Color(0xFF9C8CFF),
                        secondary = Color(0xFF66D9C2),
                        background = Color(0xFF0E0B18),
                        surface = Color(0xFF171227)
                    )
                } else {
                    lightColorScheme(
                        primary = Color(0xFF5B3FD6),
                        secondary = Color(0xFF00897B)
                    )
                }
            ) {
                HomeScreen(vm = vm, incomingLink = sharedLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Links shared into the app from a browser land straight in the input box. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedLink.value = it }
        }
    }
}
