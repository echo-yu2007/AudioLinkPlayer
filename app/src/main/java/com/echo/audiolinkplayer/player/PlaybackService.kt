package com.echo.audiolinkplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.echo.audiolinkplayer.MainActivity
import com.echo.audiolinkplayer.core.Extractor
import com.echo.audiolinkplayer.core.FormatPicker
import com.echo.audiolinkplayer.core.Settings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A MediaSessionService keeps a foreground notification alive, so playback
 * survives screen-off and swiping the app away — the whole point of this app.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    // Player calls must happen on the main thread; Extractor hops to IO on its own.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(PlayerSources.MergingSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 2_000, 5_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()

        // Holds a partial wake lock + wifi lock while playing so the CPU and radio
        // keep feeding the buffer after the screen goes off.
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.repeatMode = Settings.repeatMode
        player.shuffleModeEnabled = Settings.shuffle
        player.setPlaybackSpeed(Settings.speed)
        player.addListener(PlayerWatcher(player))

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Keep running when the task is swiped away, as long as something is playing.
        if (player == null || (!player.playWhenReady) || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    /**
     * Rebuilds the playback URI that Media3 strips when items cross the
     * controller/session boundary.
     */
    private inner class SessionCallback : MediaSession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItems::rehydrate).toMutableList())

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems.map(MediaItems::rehydrate).toMutableList(),
                    startIndex,
                    startPositionMs
                )
            )
    }

    /**
     * Extracted stream URLs are signed and expire. When one dies mid-playback the
     * player reports a source/403 error; we silently re-extract the original page
     * and resume from the same position.
     */
    private inner class PlayerWatcher(private val player: ExoPlayer) : Player.Listener {

        private var lastRefreshedItem: String? = null

        override fun onRepeatModeChanged(repeatMode: Int) {
            Settings.repeatMode = repeatMode
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            Settings.shuffle = shuffleModeEnabled
        }

        override fun onPlayerError(error: PlaybackException) {
            val recoverable = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
                else -> false
            }
            if (!recoverable) return

            val index = player.currentMediaItemIndex
            val item = player.currentMediaItem ?: return
            val sourceUrl = MediaItems.sourceUrlOf(item) ?: return
            if (lastRefreshedItem == item.mediaId + error.timestampMs) return
            lastRefreshedItem = item.mediaId + error.timestampMs

            val position = player.currentPosition
            refreshJob?.cancel()
            refreshJob = scope.launch {
                runCatching {
                    Extractor.invalidate(sourceUrl)
                    val info = Extractor.mediaInfo(this@PlaybackService, sourceUrl, force = true)
                    val selection = FormatPicker.pick(info, MediaItems.modeOf(item), Settings.quality)
                        ?: return@runCatching
                    val fresh = item.buildUpon()
                        .setUri(selection.primary.url)
                        .build()
                        .let { rebuilt ->
                            // Keep the extras in sync with the new URLs.
                            val extras = rebuilt.requestMetadata.extras
                            extras?.putString(MediaItems.KEY_PLAY_URI, selection.primary.url)
                            extras?.putString(MediaItems.KEY_AUDIO_URI, selection.extraAudio?.url)
                            rebuilt
                        }
                    player.replaceMediaItem(index, fresh)
                    player.seekTo(index, position)
                    player.prepare()
                    player.play()
                }
            }
        }
    }
}
