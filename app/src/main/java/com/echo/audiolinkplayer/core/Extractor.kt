package com.echo.audiolinkplayer.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/**
 * Thin wrapper around the embedded yt-dlp. Everything is metadata-only:
 * we ask for JSON, pull the direct stream URLs out of it and hand them to
 * ExoPlayer. Nothing is ever written to the user's storage.
 */
object Extractor {

    private const val TAG = "Extractor"

    private val initMutex = Mutex()
    private val runMutex = Mutex() // yt-dlp processes are heavy; run one at a time
    @Volatile private var initialized = false
    @Volatile var engineVersion: String? = null
        private set

    private val infoCache =
        Collections.synchronizedMap(object : LinkedHashMap<String, MediaInfo>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaInfo>) = size > 24
        })

    suspend fun ensureInit(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                YoutubeDL.init(context.applicationContext)
                engineVersion = runCatching { YoutubeDL.version(context) }.getOrNull()
            }
            initialized = true
        }
    }

    /** Pulls the newest yt-dlp. This is what keeps site support alive. */
    suspend fun updateEngine(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): EngineUpdater.Result = withContext(Dispatchers.IO) {
        ensureInit(context)
        runMutex.withLock {
            val result = EngineUpdater.update(context, Settings.nightlyEngine, onProgress)
            engineVersion = result.version ?: engineVersion
            Diagnostics.add("更新引擎", result.detail)
            result
        }
    }

    /** Ask the engine what it really is, rather than trusting a stored string. */
    suspend fun refreshVersion(context: Context): String? {
        engineVersion = EngineUpdater.currentVersion(context)
        return engineVersion
    }

    private fun baseRequest(context: Context, url: String): YoutubeDLRequest =
        YoutubeDLRequest(url).apply {
            addOption("--no-warnings")
            addOption("--ignore-config")
            addOption("--socket-timeout", "20")
            addOption("--retries", "3")
            addOption("--geo-bypass")
            addOption("--no-check-certificates")
            Settings.proxy(context)?.let { addOption("--proxy", it) }
            Settings.userAgent(context)?.let { addOption("--user-agent", it) }
            val cookies = CookieStore.cookieFile(context)
            if (cookies.exists() && cookies.length() > 0) {
                addOption("--cookies", cookies.absolutePath)
            }
        }

    /**
     * First stage: resolve a pasted link into one or more queue entries.
     * Playlists are kept flat so adding a 200-video playlist stays instant and cheap;
     * each entry is fully resolved only when it is about to play.
     */
    suspend fun resolveLinks(context: Context, url: String): List<Track> = withContext(Dispatchers.IO) {
        ensureInit(context)
        val json = runMutex.withLock {
            val req = baseRequest(context, url)
                .addOption("-J")
                .addOption("--flat-playlist")
            run(req, "解析链接 $url")
        }

        if (json.optString("_type") == "playlist") {
            val entries: JSONArray = json.optJSONArray("entries") ?: JSONArray()
            (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                val entryUrl = e.optString("url").ifEmpty { e.optString("webpage_url") }
                if (entryUrl.isEmpty()) return@mapNotNull null
                Track(
                    id = newId(),
                    sourceUrl = entryUrl,
                    title = e.optString("title").ifEmpty { entryUrl },
                    uploader = e.optString("uploader").takeIf { it.isNotEmpty() },
                    thumbnail = pickThumbnail(e),
                    durationMs = (e.optDouble("duration", 0.0) * 1000).toLong(),
                    isLive = e.optBoolean("is_live")
                )
            }
        } else {
            val info = parseInfo(url, json)
            infoCache[url] = info
            listOf(
                Track(
                    id = newId(),
                    sourceUrl = info.sourceUrl,
                    title = info.title,
                    uploader = info.uploader,
                    thumbnail = info.thumbnail,
                    durationMs = info.durationMs,
                    isLive = info.isLive
                )
            )
        }
    }

    /**
     * Second stage: full extraction with every format and its required headers.
     * Cached for as long as the signed URLs stay valid.
     */
    suspend fun mediaInfo(context: Context, sourceUrl: String, force: Boolean = false): MediaInfo =
        withContext(Dispatchers.IO) {
            if (!force) infoCache[sourceUrl]?.takeIf { it.isFresh }?.let { return@withContext it }
            ensureInit(context)
            val json = runMutex.withLock {
                val req = baseRequest(context, sourceUrl)
                    .addOption("-J")
                    .addOption("--no-playlist")
                run(req, "提取流地址 $sourceUrl")
            }
            val info = parseInfo(sourceUrl, json)
            infoCache[sourceUrl] = info
            info
        }

    /** Runs a request, logging the full command and any stderr for diagnostics. */
    private fun run(req: YoutubeDLRequest, what: String): JSONObject {
        Diagnostics.add(what, "yt-dlp " + req.buildCommand().joinToString(" "))
        try {
            val response = YoutubeDL.execute(req, null, false, null)
            if (response.err.isNotBlank()) Diagnostics.add("$what · stderr", response.err)
            val out = response.out
            val brace = out.indexOf('{')
            if (brace < 0) {
                Diagnostics.add("$what · 无输出", out.take(2000))
                throw YoutubeDLException(response.err.ifBlank { "yt-dlp 没有返回任何数据" })
            }
            return JSONObject(out.substring(brace))
        } catch (e: Throwable) {
            Diagnostics.add("$what · 失败", e.message ?: e.toString())
            throw e
        }
    }

    fun invalidate(sourceUrl: String) {
        infoCache.remove(sourceUrl)
    }

    // ---------------------------------------------------------------- parsing

    private fun parseInfo(sourceUrl: String, o: JSONObject): MediaInfo {
        val formats = mutableListOf<StreamFormat>()
        o.optJSONArray("formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { f -> parseFormat(f)?.let(formats::add) }
            }
        }
        // Some extractors return a bare url with no formats array.
        if (formats.isEmpty()) parseFormat(o)?.let(formats::add)

        return MediaInfo(
            sourceUrl = o.optString("webpage_url").ifEmpty { sourceUrl },
            title = o.optString("title").ifEmpty { sourceUrl },
            uploader = o.optString("uploader").takeIf { it.isNotEmpty() && it != "null" },
            thumbnail = pickThumbnail(o),
            durationMs = (o.optDouble("duration", 0.0) * 1000).toLong(),
            isLive = o.optBoolean("is_live"),
            formats = formats
        )
    }

    private fun parseFormat(f: JSONObject): StreamFormat? {
        val url = f.optString("url")
        if (url.isEmpty()) return null
        // Skip things ExoPlayer cannot play back directly.
        val protocol = f.optString("protocol")
        if (protocol.startsWith("m3u8") && f.optString("ext") == "mhtml") return null
        if (protocol == "mhtml") return null

        val headers = mutableMapOf<String, String>()
        f.optJSONObject("http_headers")?.let { h ->
            h.keys().forEach { k -> headers[k] = h.optString(k) }
        }
        HeaderStore.remember(url, headers)

        return StreamFormat(
            formatId = f.optString("format_id").ifEmpty { "0" },
            url = url,
            ext = f.optString("ext"),
            protocol = protocol,
            height = f.optInt("height", 0),
            fps = f.optDouble("fps", 0.0),
            tbr = f.optDouble("tbr", 0.0),
            abr = f.optDouble("abr", 0.0),
            vcodec = f.optString("vcodec", "none"),
            acodec = f.optString("acodec", "none"),
            filesize = f.optLong("filesize", f.optLong("filesize_approx", 0L)),
            headers = headers
        )
    }

    private fun pickThumbnail(o: JSONObject): String? {
        o.optString("thumbnail").takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        val arr = o.optJSONArray("thumbnails") ?: return null
        var best: String? = null
        var bestWidth = -1
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val w = t.optInt("width", 0)
            if (w >= bestWidth) {
                bestWidth = w
                best = t.optString("url").takeIf { it.isNotEmpty() }
            }
        }
        return best
    }

    private var counter = 0L
    @Synchronized
    private fun newId(): String = "t${System.currentTimeMillis()}_${counter++}"

    fun logDir(context: Context): File = File(context.cacheDir, "ytdlp-logs").apply { mkdirs() }

    fun log(msg: String) = Log.d(TAG, msg)
}
