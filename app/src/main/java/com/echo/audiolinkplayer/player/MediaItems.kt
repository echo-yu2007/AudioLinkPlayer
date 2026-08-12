package com.echo.audiolinkplayer.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.echo.audiolinkplayer.core.PlayMode
import com.echo.audiolinkplayer.core.Selection
import com.echo.audiolinkplayer.core.Track

/**
 * MediaItem.localConfiguration (the actual playback URI) is dropped when an item
 * crosses the MediaController -> MediaSession boundary, so every field the service
 * needs to rebuild the item lives in requestMetadata.extras instead.
 */
object MediaItems {

    const val KEY_PLAY_URI = "playUri"
    const val KEY_AUDIO_URI = "audioUri"
    const val KEY_SOURCE_URL = "sourceUrl"
    const val KEY_MIME = "mime"
    const val KEY_MODE = "mode"
    const val KEY_QUALITY = "quality"

    fun build(track: Track, selection: Selection, mode: PlayMode): MediaItem {
        val primary = selection.primary
        val mime = when {
            primary.isHls -> MimeTypes.APPLICATION_M3U8
            primary.isDash -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val extras = Bundle().apply {
            putString(KEY_PLAY_URI, primary.url)
            putString(KEY_AUDIO_URI, selection.extraAudio?.url)
            putString(KEY_SOURCE_URL, track.sourceUrl)
            putString(KEY_MIME, mime)
            putString(KEY_MODE, mode.name)
            putString(KEY_QUALITY, selection.label)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.uploader ?: "AudioLinkPlayer")
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { track.thumbnail?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(primary.url)
            .apply { mime?.let { setMimeType(it) } }
            .setMediaMetadata(metadata)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(track.sourceUrl))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** Re-attach the playback URI that the session boundary stripped away. */
    fun rehydrate(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val extras = item.requestMetadata.extras ?: return item
        val playUri = extras.getString(KEY_PLAY_URI) ?: return item
        val mime = extras.getString(KEY_MIME)
        return item.buildUpon()
            .setUri(playUri)
            .apply { mime?.let { setMimeType(it) } }
            .build()
    }

    fun sourceUrlOf(item: MediaItem?): String? =
        item?.requestMetadata?.extras?.getString(KEY_SOURCE_URL)

    fun audioUriOf(item: MediaItem?): String? =
        item?.requestMetadata?.extras?.getString(KEY_AUDIO_URI)

    fun modeOf(item: MediaItem?): PlayMode =
        runCatching { PlayMode.valueOf(item?.requestMetadata?.extras?.getString(KEY_MODE) ?: "") }
            .getOrDefault(PlayMode.AUDIO_ONLY)

    fun qualityOf(item: MediaItem?): String? =
        item?.requestMetadata?.extras?.getString(KEY_QUALITY)
}
