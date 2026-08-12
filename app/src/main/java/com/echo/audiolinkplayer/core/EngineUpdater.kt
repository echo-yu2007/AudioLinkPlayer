package com.echo.audiolinkplayer.core

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Replaces the library's own updater.
 *
 * The stock one only talks to api.github.com and swallows failures, which is
 * exactly the wrong behaviour on a network where GitHub is unreliable: the app
 * silently keeps running the yt-dlp that shipped inside the APK. A yt-dlp that is
 * a few months old fails on the big tube sites with plain HTTP 403, because those
 * sites change their gates constantly.
 *
 * So: download the yt-dlp zipapp straight from the release URL, fall back through
 * mirrors, verify the result actually runs, and report what went wrong out loud.
 */
object EngineUpdater {

    private const val PREFS = "youtubedl-android"
    private const val KEY_VERSION = "dlpVersion"
    private const val KEY_VERSION_NAME = "dlpVersionName"

    private const val STABLE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    private const val NIGHTLY =
        "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp"

    /** Mirror prefixes that proxy raw github.com URLs. Tried in order. */
    private val MIRRORS = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/"
    )

    data class Result(val ok: Boolean, val version: String?, val detail: String)

    private fun binaryFile(context: Context): File {
        val base = File(context.noBackupFilesDir, YoutubeDL.baseName)
        return File(File(base, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
    }

    fun storedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VERSION, null)

    private fun storeVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERSION, version)
            .putString(KEY_VERSION_NAME, version)
            .apply()
    }

    /** Asks the engine itself what version it is — the only answer worth trusting. */
    suspend fun currentVersion(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            Extractor.ensureInit(context)
            val req = YoutubeDLRequest(emptyList<String>()).addOption("--version")
            YoutubeDL.execute(req, null, false, null).out.trim().lines().lastOrNull()
        }.getOrNull()
    }

    suspend fun update(
        context: Context,
        nightly: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        runCatching { Extractor.ensureInit(context) }

        val target = if (nightly) NIGHTLY else STABLE
        val candidates = buildList {
            Settings.customEngineUrl(context)?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            MIRRORS.forEach { add(it + target) }
        }

        val client = Http.client(context)
        val tmp = File(context.cacheDir, "yt-dlp.download")
        val failures = StringBuilder()

        for (url in candidates) {
            val label = if (url.startsWith("https://github.com")) "GitHub 直连" else shortHost(url)
            onProgress("正在从 $label 下载…")
            val downloaded = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                // The zipapp is ~3 MB and starts with a shebang; an HTML error page is neither.
                if (tmp.length() < 1_000_000L) error("文件太小 (${tmp.length()} 字节)，多半是错误页")
                val head = tmp.inputStream().use { ByteArray(2).also { b -> it.read(b) } }
                val magic = String(head)
                if (magic != "#!" && magic != "PK") error("下载到的不是 yt-dlp")
                true
            }.onFailure { e ->
                failures.append("• $label：${e.message}\n")
                tmp.delete()
            }.getOrDefault(false)

            if (!downloaded) continue

            onProgress("正在安装…")
            val installed = runCatching {
                val binary = binaryFile(context)
                binary.parentFile?.mkdirs()
                val backup = File(binary.parentFile, "yt-dlp.bak")
                if (binary.exists()) binary.copyTo(backup, overwrite = true)
                tmp.copyTo(binary, overwrite = true)
                binary.setExecutable(true)

                val version = runVersion(context)
                if (version.isNullOrBlank()) {
                    // Roll back rather than leave the user with a broken engine.
                    if (backup.exists()) backup.copyTo(binary, overwrite = true)
                    error("新版本无法运行，已回滚")
                }
                backup.delete()
                storeVersion(context, version)
                version
            }.onFailure { e -> failures.append("• 安装：${e.message}\n") }

            tmp.delete()
            installed.getOrNull()?.let { v ->
                return@withContext Result(true, v, "已更新到 $v（来源：$label）")
            }
        }

        tmp.delete()
        Result(
            ok = false,
            version = runVersion(context),
            detail = "所有下载源都失败了：\n$failures\n" +
                "如果你在用代理/VPN，请到设置里把代理地址填上（例如 http://127.0.0.1:7890），" +
                "或者自己找一个能下载的 yt-dlp 直链填进「自定义更新地址」。"
        )
    }

    private fun runVersion(context: Context): String? = runCatching {
        val req = YoutubeDLRequest(emptyList<String>()).addOption("--version")
        YoutubeDL.execute(req, null, false, null).out.trim().lines().lastOrNull()
            ?.takeIf { it.isNotBlank() && it.first().isDigit() }
    }.getOrNull()

    /** yt-dlp releases are date-stamped, so staleness is readable straight off the version. */
    fun isStale(version: String?): Boolean {
        val m = Regex("^(\\d{4})\\.(\\d{2})\\.(\\d{2})").find(version.orEmpty()) ?: return true
        val (y, mo, d) = m.destructured
        val released = runCatching {
            java.util.GregorianCalendar(y.toInt(), mo.toInt() - 1, d.toInt()).timeInMillis
        }.getOrNull() ?: return true
        return System.currentTimeMillis() - released > 60L * 24 * 3600 * 1000
    }

    private fun shortHost(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)
}

/** Shared OkHttp client that honours the user's proxy setting. */
object Http {
    fun client(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
        Settings.proxy(context)?.let { spec ->
            parseProxy(spec)?.let { builder.proxy(it) }
        }
        return builder.build()
    }

    /** Accepts host:port, http://host:port and socks5://host:port. */
    fun parseProxy(spec: String): java.net.Proxy? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        val socks = s.startsWith("socks", ignoreCase = true)
        val bare = s.substringAfter("://", s)
        val host = bare.substringBeforeLast(':', "").ifEmpty { return null }
        val port = bare.substringAfterLast(':', "").toIntOrNull() ?: return null
        return java.net.Proxy(
            if (socks) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress(host, port)
        )
    }
}
