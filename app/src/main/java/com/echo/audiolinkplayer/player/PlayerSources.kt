package com.echo.audiolinkplayer.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.echo.audiolinkplayer.core.HeaderStore
import com.echo.audiolinkplayer.core.Http
import com.echo.audiolinkplayer.core.Settings
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@UnstableApi
object PlayerSources {

    @Volatile private var cache: SimpleCache? = null

    /**
     * Bounded LRU disk cache. This is a scrub/seek buffer, not a download folder:
     * it lives in cacheDir so the OS can reclaim it, and the user can cap or
     * disable it in settings.
     */
    @Synchronized
    fun cache(context: Context): SimpleCache? {
        val limitMb = Settings.cacheMb
        if (limitMb <= 0) return null
        cache?.let { return it }
        val dir = File(context.cacheDir, "media")
        return runCatching {
            SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(limitMb * 1024L * 1024L),
                StandaloneDatabaseProvider(context)
            ).also { cache = it }
        }.getOrNull()
    }

    @Synchronized
    fun clearCache(context: Context): Boolean {
        val c = cache
        cache = null
        runCatching { c?.release() }
        return File(context.cacheDir, "media").deleteRecursively()
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        // If the page needed a proxy to resolve, the media CDN almost certainly
        // needs it too, so the stream goes through the same proxy.
        val proxy = Settings.proxy(context)?.let(Http::parseProxy)
        val http: DataSource.Factory = if (proxy != null) {
            OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            )
        } else {
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(20_000)
                .setKeepPostFor302Redirects(true)
        }

        // Per-request header injection: ExoPlayer has no per-item header API.
        val resolving = ResolvingDataSource.Factory(http) { spec: DataSpec ->
            val headers = HeaderStore.headersFor(spec.uri)
            if (headers.isEmpty()) spec else spec.withRequestHeaders(headers)
        }

        val c = cache(context) ?: return resolving
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { spec ->
                // Signed URLs change their query string on every refresh; key on the
                // stable part so a re-resolved stream still hits the cache.
                spec.key ?: (spec.uri.host.orEmpty() + spec.uri.path.orEmpty())
            }
    }

    /**
     * Wraps the default factory so that a video-only stream paired with a separate
     * audio stream (YouTube-style DASH) is merged into a single playback.
     */
    @UnstableApi
    class MergingSourceFactory(context: Context) : MediaSource.Factory {

        private val delegate = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory(context))

        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(p)
            return this
        }

        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(p)
            return this
        }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val item = MediaItems.rehydrate(mediaItem)
            val main = delegate.createMediaSource(item)
            val audioUri = MediaItems.audioUriOf(item) ?: return main
            val audioItem = item.buildUpon()
                .setMediaId(item.mediaId + ":audio")
                .setUri(audioUri)
                .setMimeType(null)
                .build()
            return MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ false,
                main,
                delegate.createMediaSource(audioItem)
            )
        }
    }
}
