package com.echo.audiolinkplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.echo.audiolinkplayer.core.CookieStore
import com.echo.audiolinkplayer.core.Diagnostics
import com.echo.audiolinkplayer.core.EngineUpdater
import com.echo.audiolinkplayer.core.Extractor
import com.echo.audiolinkplayer.core.FormatPicker
import com.echo.audiolinkplayer.core.MediaInfo
import com.echo.audiolinkplayer.core.PlayMode
import com.echo.audiolinkplayer.core.PlaylistStore
import com.echo.audiolinkplayer.core.QualityCap
import com.echo.audiolinkplayer.core.Settings
import com.echo.audiolinkplayer.core.StreamFormat
import com.echo.audiolinkplayer.core.Track
import com.echo.audiolinkplayer.player.MediaItems
import com.echo.audiolinkplayer.player.PlaybackService
import com.echo.audiolinkplayer.player.PlayerSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentTrackId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val hasVideo: Boolean = false,
    val qualityLabel: String? = null
)

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Track ids whose stream is already sitting in the player queue. */
    private val resolvedIds = LinkedHashSet<String>()

    private val _resolving = MutableStateFlow<Set<String>>(emptySet())
    val resolving: StateFlow<Set<String>> = _resolving.asStateFlow()

    private val _playMode = MutableStateFlow(Settings.playMode)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val _quality = MutableStateFlow(Settings.quality)
    val quality: StateFlow<QualityCap> = _quality.asStateFlow()

    private val _formatOptions = MutableStateFlow<List<StreamFormat>>(emptyList())
    val formatOptions: StateFlow<List<StreamFormat>> = _formatOptions.asStateFlow()

    private val _engineVersion = MutableStateFlow<String?>(null)
    val engineVersion: StateFlow<String?> = _engineVersion.asStateFlow()

    /** Non-null while an update-result sheet should be on screen. */
    private val _updateReport = MutableStateFlow<String?>(null)
    val updateReport: StateFlow<String?> = _updateReport.asStateFlow()

    init {
        connect()
        viewModelScope.launch {
            _tracks.value = PlaylistStore.load(getApplication())
        }
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    if (c.isPlaying || _ui.value.positionMs == 0L) {
                        _ui.value = _ui.value.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0),
                            durationMs = c.duration.takeIf { it > 0 } ?: 0
                        )
                    }
                }
                delay(500)
            }
        }
        viewModelScope.launch {
            runCatching { Extractor.ensureInit(getApplication()) }
            _engineVersion.value = runCatching { Extractor.refreshVersion(getApplication()) }
                .getOrNull()
        }
    }

    private fun connect() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.let { c ->
                c.repeatMode = Settings.repeatMode
                c.shuffleModeEnabled = Settings.shuffle
                c.setPlaybackSpeed(Settings.speed)
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = syncUi()
                })
                syncUi()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
    }

    private fun syncUi() {
        val c = controller ?: return
        val item: MediaItem? = c.currentMediaItem
        _ui.value = _ui.value.copy(
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            currentTrackId = item?.mediaId,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            speed = c.playbackParameters.speed,
            repeatMode = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
            hasVideo = MediaItems.modeOf(item) == PlayMode.VIDEO,
            qualityLabel = MediaItems.qualityOf(item)
        )
    }

    // ------------------------------------------------------------------ queue

    fun addLink(rawInput: String) {
        val urls = rawInput.split(Regex("[\\s\\n]+"))
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        if (urls.isEmpty()) {
            _message.value = "没找到有效链接（需要以 http 开头）"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            var added = 0
            for (url in urls) {
                val result = runCatching { Extractor.resolveLinks(getApplication(), url) }
                result.onSuccess { list ->
                    if (list.isEmpty()) {
                        _message.value = "没解析到内容：$url"
                    } else {
                        _tracks.value = _tracks.value + list
                        added += list.size
                    }
                }.onFailure { e ->
                    _message.value = friendlyError(e)
                }
            }
            _busy.value = false
            if (added > 0) {
                _message.value = "已添加 $added 个"
                PlaylistStore.save(getApplication(), _tracks.value)
                // Warm up the first item so tapping play is instant.
                _tracks.value.firstOrNull { it.id !in resolvedIds }?.let { prepare(it) }
            }
        }
    }

    fun remove(track: Track) {
        viewModelScope.launch {
            val idx = playerIndexOf(track)
            if (track.id in resolvedIds) {
                resolvedIds.remove(track.id)
                runCatching { controller?.removeMediaItem(idx) }
            }
            _tracks.value = _tracks.value.filterNot { it.id == track.id }
            PlaylistStore.save(getApplication(), _tracks.value)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            controller?.clearMediaItems()
            resolvedIds.clear()
            _tracks.value = emptyList()
            PlaylistStore.save(getApplication(), emptyList())
        }
    }

    /** Position of [track] inside the player queue (queue holds only resolved tracks). */
    private fun playerIndexOf(track: Track): Int {
        val listIdx = _tracks.value.indexOfFirst { it.id == track.id }
        if (listIdx < 0) return 0
        return _tracks.value.take(listIdx).count { it.id in resolvedIds }
    }

    private suspend fun prepare(track: Track): Boolean {
        if (track.id in resolvedIds) return true
        if (track.id in _resolving.value) return false
        _resolving.value = _resolving.value + track.id
        return try {
            val info = Extractor.mediaInfo(getApplication(), track.sourceUrl)
            val selection = FormatPicker.pick(info, _playMode.value, _quality.value)
            if (selection == null) {
                _message.value = "没有可播放的流：${track.title}"
                return false
            }
            // Refresh metadata we only learn about after the full extraction.
            val enriched = track.copy(
                title = track.title.takeIf { it != track.sourceUrl } ?: info.title,
                thumbnail = track.thumbnail ?: info.thumbnail,
                durationMs = if (track.durationMs > 0) track.durationMs else info.durationMs,
                uploader = track.uploader ?: info.uploader
            )
            _tracks.value = _tracks.value.map { if (it.id == track.id) enriched else it }

            val item = MediaItems.build(enriched, selection, _playMode.value)
            withContext(Dispatchers.Main) {
                val c = controller ?: return@withContext
                c.addMediaItem(playerIndexOf(enriched), item)
                resolvedIds.add(enriched.id)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
            }
            true
        } catch (e: Throwable) {
            _message.value = friendlyError(e)
            false
        } finally {
            _resolving.value = _resolving.value - track.id
        }
    }

    fun play(track: Track) {
        viewModelScope.launch {
            if (track.id !in resolvedIds) {
                _busy.value = true
                val ok = prepare(track)
                _busy.value = false
                if (!ok) return@launch
            }
            val c = controller ?: return@launch
            c.seekTo(playerIndexOf(track), 0)
            c.prepare()
            c.play()
            loadFormatOptions(track)
            // Fill in the rest of the queue so repeat-all and auto-advance cover everything.
            resolveRestInBackground()
        }
    }

    private fun resolveRestInBackground() {
        viewModelScope.launch {
            for (t in _tracks.value) {
                if (t.id in resolvedIds) continue
                prepare(t)
            }
        }
    }

    private suspend fun loadFormatOptions(track: Track) {
        val info: MediaInfo = runCatching {
            Extractor.mediaInfo(getApplication(), track.sourceUrl)
        }.getOrNull() ?: return
        _formatOptions.value = FormatPicker.videoOptions(info)
    }

    // --------------------------------------------------------------- controls

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun setSpeed(speed: Float) {
        Settings.speed = speed
        controller?.setPlaybackSpeed(speed)
        syncUi()
    }

    /** Cycles OFF -> ALL -> ONE. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        Settings.repeatMode = c.repeatMode
        syncUi()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        Settings.shuffle = c.shuffleModeEnabled
        syncUi()
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        Settings.playMode = mode
        reloadCurrent()
    }

    fun setQuality(cap: QualityCap) {
        _quality.value = cap
        Settings.quality = cap
        if (_playMode.value == PlayMode.VIDEO) reloadCurrent()
    }

    /** Re-resolve the currently playing item under the new mode/quality, keeping position. */
    private fun reloadCurrent() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val track = _tracks.value.firstOrNull { it.id == id } ?: return
        val position = c.currentPosition
        val wasPlaying = c.isPlaying
        val index = c.currentMediaItemIndex

        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val info = Extractor.mediaInfo(getApplication(), track.sourceUrl)
                val selection = FormatPicker.pick(info, _playMode.value, _quality.value)
                    ?: return@runCatching
                val item = MediaItems.build(track, selection, _playMode.value)
                withContext(Dispatchers.Main) {
                    c.replaceMediaItem(index, item)
                    c.seekTo(index, position)
                    c.prepare()
                    if (wasPlaying) c.play()
                }
            }.onFailure { _message.value = friendlyError(it) }
            _busy.value = false
            syncUi()
        }
    }

    /** Force a specific rendition for the current item. */
    fun selectFormat(format: StreamFormat) {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val track = _tracks.value.firstOrNull { it.id == id } ?: return
        val position = c.currentPosition
        val index = c.currentMediaItemIndex
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val info = Extractor.mediaInfo(getApplication(), track.sourceUrl)
                val selection = FormatPicker.forFormat(info, format)
                val item = MediaItems.build(track, selection, PlayMode.VIDEO)
                withContext(Dispatchers.Main) {
                    c.replaceMediaItem(index, item)
                    c.seekTo(index, position)
                    c.prepare()
                    c.play()
                }
                _playMode.value = PlayMode.VIDEO
            }.onFailure { _message.value = friendlyError(it) }
            _busy.value = false
            syncUi()
        }
    }

    fun player(): Player? = controller

    // -------------------------------------------------------------- settings

    fun updateEngine() {
        viewModelScope.launch {
            _busy.value = true
            _updateReport.value = "正在准备…"
            val result = runCatching {
                Extractor.updateEngine(getApplication()) { step ->
                    _updateReport.value = step
                }
            }.getOrElse {
                EngineUpdater.Result(false, null, "更新失败：${it.message}")
            }
            _engineVersion.value = result.version ?: _engineVersion.value
            Settings.lastUpdateCheck = System.currentTimeMillis()
            _updateReport.value = result.detail
            _busy.value = false
        }
    }

    fun dismissUpdateReport() {
        _updateReport.value = null
    }

    fun engineIsStale(): Boolean = EngineUpdater.isStale(_engineVersion.value)

    fun diagnostics(): String = buildString {
        appendLine("yt-dlp: ${_engineVersion.value ?: "未知"}")
        appendLine("代理: ${Settings.proxySpec.ifBlank { "未设置" }}")
        appendLine("已保存 Cookie 域名: ${cookieDomains().joinToString(", ").ifBlank { "无" }}")
        appendLine()
        append(Diagnostics.dump())
    }

    fun setProxy(spec: String) {
        Settings.proxySpec = spec
        _message.value = if (spec.isBlank()) "已关闭代理" else "代理已设为 $spec"
    }

    fun clearCache() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { PlayerSources.clearCache(getApplication()) }
            _message.value = if (ok) "缓存已清空" else "缓存清理失败"
        }
    }

    fun clearCookies() {
        CookieStore.clear(getApplication())
        _message.value = "已清除登录信息"
    }

    fun cookieDomains(): List<String> = CookieStore.savedDomains(getApplication())

    fun consumeMessage() {
        _message.value = null
    }

    private fun friendlyError(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            // The single most common failure: an out-of-date yt-dlp gets bounced by
            // the site's bot gate long before it ever reaches the video.
            raw.contains("403") || raw.contains("Forbidden", true) ->
                "被网站拒绝了 (403)。多半是解析引擎过期——去设置里点「立即更新」；" +
                    "更新完还不行的话，可能需要在设置里填代理地址。"
            raw.contains("Unsupported URL", true) -> "这个站点/链接暂不支持，试试更新解析引擎"
            raw.contains("Sign in", true) || raw.contains("log in", true) ->
                "需要登录，请到设置里用内置浏览器登录该网站"
            raw.contains("private", true) -> "这是私有内容，需要登录后才能访问"
            raw.contains("not available in your country", true) || raw.contains("geo", true) ->
                "该内容有地区限制，试试在设置里填代理地址"
            raw.contains("timed out", true) || raw.contains("Connection", true) ->
                "连不上这个网站。如果你在用代理/VPN，去设置里把代理地址填上。"
            raw.isBlank() -> "解析失败：${e.javaClass.simpleName}"
            else -> "解析失败：" + raw.lines().lastOrNull { it.isNotBlank() }?.take(200)
        }
    }
}
