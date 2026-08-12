package com.echo.audiolinkplayer.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Extraction fallback that runs the page in the system WebView.
 *
 * Some sites — Pornhub is the loud example — now fingerprint the TLS handshake
 * and reject anything that isn't a real browser. yt-dlp's answer is
 * `--impersonate`, which needs curl_cffi, which does not build on Android. So the
 * request has to genuinely come from a browser: we load the page in the WebView,
 * watch what the page's own player asks for, and keep the media URLs.
 *
 * Nothing is downloaded here. We only observe the addresses and hand them to
 * ExoPlayer along with the headers the page would have used.
 */
object WebExtractor {

    private val MEDIA_PATTERN = Regex(
        """\.(m3u8|mpd|mp4|m4a|webm|ts)(\?|$)""", RegexOption.IGNORE_CASE
    )
    private val SKIP_PATTERN = Regex(
        """\.(jpg|jpeg|png|gif|webp|svg|css|js|woff2?|ico)(\?|$)""", RegexOption.IGNORE_CASE
    )

    suspend fun extract(
        context: Context,
        pageUrl: String,
        timeoutMs: Long = 35_000
    ): MediaInfo? {
        Diagnostics.add("浏览器解析", "开始加载 $pageUrl")
        val info = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) { load(context.applicationContext, pageUrl) }
        }
        if (info == null || info.formats.isEmpty()) {
            Diagnostics.add("浏览器解析", "没有嗅探到可播放的流")
            return null
        }
        Diagnostics.add(
            "浏览器解析",
            "找到 ${info.formats.size} 个流：" + info.formats.joinToString { it.label }
        )
        return info
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun load(context: Context, pageUrl: String): MediaInfo? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            // Preserves discovery order; the page's own player usually asks first.
            val sniffed = LinkedHashMap<String, MutableMap<String, String>>()
            var pageTitle: String? = null
            var thumbnail: String? = null
            var durationMs = 0L
            var scripted = JSONArray()

            lateinit var web: WebView

            fun finish() {
                if (!done.compareAndSet(false, true)) return
                val formats = buildFormats(pageUrl, sniffed, scripted, web.settings.userAgentString)
                val info = MediaInfo(
                    sourceUrl = pageUrl,
                    title = cleanTitle(pageTitle) ?: pageUrl,
                    uploader = null,
                    thumbnail = thumbnail,
                    durationMs = durationMs,
                    isLive = false,
                    formats = formats
                )
                runCatching {
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.destroy()
                }
                if (cont.isActive) cont.resume(info)
            }

            web = WebView(context)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = false // we only want the media URLs
                mediaPlaybackRequiresUserGesture = false // let the player start on its own
                userAgentString = userAgentString.replace("; wv", "")
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

            web.addJavascriptInterface(object {
                @JavascriptInterface
                fun onResult(json: String) {
                    handler.post {
                        runCatching {
                            val o = JSONObject(json)
                            pageTitle = o.optString("title").takeIf { it.isNotEmpty() } ?: pageTitle
                            thumbnail = o.optString("thumb").takeIf { it.isNotEmpty() } ?: thumbnail
                            val d = o.optDouble("duration", 0.0)
                            if (d > 0) durationMs = (d * 1000).toLong()
                            scripted = o.optJSONArray("streams") ?: JSONArray()
                        }
                        // Give the player a last moment to fire its own requests.
                        handler.postDelayed({ finish() }, 3_000)
                    }
                }
            }, "ALPBridge")

            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (looksLikeMedia(url)) {
                        synchronized(sniffed) {
                            sniffed.getOrPut(url) {
                                (request.requestHeaders ?: emptyMap()).toMutableMap()
                            }
                        }
                    }
                    return null // never block, we are only listening
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Let the page settle, then ask it what it is playing.
                    handler.postDelayed({
                        if (!done.get()) view?.evaluateJavascript(PROBE_JS, null)
                    }, 2_500)
                }
            }

            handler.postDelayed({ finish() }, 30_000)

            cont.invokeOnCancellation {
                handler.post {
                    done.set(true)
                    runCatching { web.destroy() }
                }
            }

            web.loadUrl(pageUrl)
        }

    private fun looksLikeMedia(url: String): Boolean {
        val path = url.substringBefore('?')
        if (SKIP_PATTERN.containsMatchIn(path)) return false
        if (MEDIA_PATTERN.containsMatchIn(url)) return true
        return url.contains("/hls/", true) || url.contains("master.m3u8", true)
    }

    private fun buildFormats(
        pageUrl: String,
        sniffed: Map<String, Map<String, String>>,
        scripted: JSONArray,
        userAgent: String?
    ): List<StreamFormat> {
        val origin = Uri.parse(pageUrl).let { "${it.scheme}://${it.host}" }
        val cookie = runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull()

        val candidates = LinkedHashMap<String, Int>() // url -> height hint

        for (i in 0 until scripted.length()) {
            val o = scripted.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            candidates[url] = o.optInt("quality", 0)
        }
        sniffed.keys.forEach { url -> candidates.putIfAbsent(url, 0) }

        return candidates.entries.mapNotNull { (url, hint) ->
            // Segment URLs are useless on their own — we want manifests and whole files.
            if (url.contains(Regex("""seg-\d+|/frag\d+|\.ts(\?|$)"""))) return@mapNotNull null

            val headers = buildMap {
                putAll(sniffed[url].orEmpty())
                userAgent?.let { put("User-Agent", it) }
                put("Referer", pageUrl)
                put("Origin", origin)
                cookie?.let { put("Cookie", it) }
            }
            HeaderStore.remember(url, headers)

            val height = if (hint > 0) hint else guessHeight(url)
            val ext = when {
                url.contains(".m3u8", true) -> "m3u8"
                url.contains(".mpd", true) -> "mpd"
                url.contains(".webm", true) -> "webm"
                else -> "mp4"
            }
            StreamFormat(
                formatId = "web-${candidates.keys.indexOf(url)}",
                url = url,
                ext = ext,
                protocol = if (ext == "m3u8") "m3u8_native" else "https",
                height = height,
                fps = 0.0,
                tbr = 0.0,
                abr = 0.0,
                // We cannot inspect codecs here; assume muxed, which is true for
                // every progressive file and every tube-site HLS rendition.
                vcodec = "unknown",
                acodec = "unknown",
                filesize = 0L,
                headers = headers
            )
        }.distinctBy { it.url }
    }

    private fun guessHeight(url: String): Int =
        Regex("""(\d{3,4})[pP][^a-z]|/(\d{3,4})/|_(\d{3,4})[kK]?\.""")
            .find(url)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()
            ?.takeIf { it in 100..4320 }
            ?: 0

    private fun cleanTitle(raw: String?): String? = raw
        ?.substringBefore(" - Pornhub")
        ?.substringBefore(" | ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("about:blank", true) }

    /**
     * Runs inside the page. Reads whatever the site's own player already knows,
     * and resolves the media-list endpoints that tube sites hide their URLs behind
     * — those fetches are same-origin, so they carry the real browser identity.
     */
    private val PROBE_JS = """
        (function () {
          function post(o) { try { ALPBridge.onResult(JSON.stringify(o)); } catch (e) {} }
          var out = { title: document.title, streams: [] };
          var pending = [];

          function addFlashvars() {
            for (var k in window) {
              if (k.indexOf('flashvars') !== 0) continue;
              var fv;
              try { fv = window[k]; } catch (e) { continue; }
              if (!fv) continue;
              if (fv.image_url) out.thumb = fv.image_url;
              if (fv.video_duration) out.duration = parseFloat(fv.video_duration);
              var defs = fv.mediaDefinitions || [];
              for (var i = 0; i < defs.length; i++) {
                var d = defs[i];
                if (!d || !d.videoUrl) continue;
                var q = parseInt(d.quality, 10) || 0;
                if (/\.(m3u8|mp4)/i.test(d.videoUrl)) {
                  out.streams.push({ url: d.videoUrl, quality: q });
                } else {
                  pending.push(d.videoUrl);
                }
              }
            }
          }

          function addDom() {
            var vids = document.querySelectorAll('video, video source');
            for (var i = 0; i < vids.length; i++) {
              var s = vids[i].src || vids[i].getAttribute('src');
              if (s && s.indexOf('http') === 0) out.streams.push({ url: s, quality: 0 });
            }
          }

          function addHtmlScan() {
            var html = document.documentElement.outerHTML;
            var re = /https?:\/\/[^"'\s\\<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s\\<>]*)?/g;
            var m, n = 0;
            while ((m = re.exec(html)) !== null && n < 40) {
              out.streams.push({ url: m[0].replace(/\\\//g, '/'), quality: 0 });
              n++;
            }
          }

          try { addFlashvars(); } catch (e) {}
          try { addDom(); } catch (e) {}
          try { addHtmlScan(); } catch (e) {}

          if (pending.length === 0) { post(out); return; }

          var left = pending.length;
          function settle() { if (--left <= 0) post(out); }
          for (var j = 0; j < pending.length; j++) {
            (function (u) {
              fetch(u, { credentials: 'include' })
                .then(function (r) { return r.json(); })
                .then(function (list) {
                  if (!list) return;
                  if (!list.length) list = [list];
                  for (var i = 0; i < list.length; i++) {
                    var it = list[i];
                    if (it && it.videoUrl) {
                      out.streams.push({ url: it.videoUrl, quality: parseInt(it.quality, 10) || 0 });
                    }
                  }
                })
                .catch(function () {})
                .then(settle, settle);
            })(pending[j]);
          }
          setTimeout(function () { post(out); }, 8000);
        })();
    """.trimIndent()
}
