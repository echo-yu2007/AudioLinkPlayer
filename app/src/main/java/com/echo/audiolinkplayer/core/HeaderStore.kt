package com.echo.audiolinkplayer.core

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Many sites (Pornhub included) reject media requests that arrive without the
 * User-Agent / Referer / Cookie combination yt-dlp used when it resolved the URL.
 * ExoPlayer has no per-MediaItem header support, so we stash the headers here and
 * a ResolvingDataSource injects them into every DataSpec.
 *
 * Keyed by host as well as by exact URL, because HLS segment URLs differ from the
 * manifest URL they came from.
 */
object HeaderStore {

    private val byUrl = ConcurrentHashMap<String, Map<String, String>>()
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    fun remember(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val clean = headers.filterKeys { !it.equals("Accept-Encoding", true) }
        byUrl[url] = clean
        Uri.parse(url).host?.let { byHost[it] = clean }
    }

    fun headersFor(uri: Uri): Map<String, String> {
        byUrl[uri.toString()]?.let { return it }
        uri.host?.let { host ->
            byHost[host]?.let { return it }
            // Segment CDNs often sit on a sibling host (ev-h1.phncdn.com vs ev.phncdn.com).
            val suffix = host.split('.').takeLast(2).joinToString(".")
            byHost.entries.firstOrNull { it.key.endsWith(suffix) }?.let { return it.value }
        }
        return emptyMap()
    }

    fun clear() {
        byUrl.clear()
        byHost.clear()
    }
}
