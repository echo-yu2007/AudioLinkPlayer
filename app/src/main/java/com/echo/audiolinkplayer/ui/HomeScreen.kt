package com.echo.audiolinkplayer.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.echo.audiolinkplayer.core.PlayMode
import com.echo.audiolinkplayer.core.QualityCap
import com.echo.audiolinkplayer.core.Settings
import com.echo.audiolinkplayer.core.Track

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, incomingLink: MutableState<String?>) {

    val tracks by vm.tracks.collectAsState()
    val ui by vm.ui.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val resolving by vm.resolving.collectAsState()
    val playMode by vm.playMode.collectAsState()
    val quality by vm.quality.collectAsState()
    val formats by vm.formatOptions.collectAsState()
    val engineVersion by vm.engineVersion.collectAsState()
    val updateReport by vm.updateReport.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var expandedPlayer by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(incomingLink.value) {
        incomingLink.value?.let {
            vm.addLink(it)
            incomingLink.value = null
        }
    }

    BackHandler(enabled = expandedPlayer) { expandedPlayer = false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("AudioLinkPlayer", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (engineVersion != null && vm.engineIsStale()) {
                StaleEngineBanner(onUpdate = { vm.updateEngine() })
            }

            ModeRow(
                playMode = playMode,
                quality = quality,
                onMode = vm::setPlayMode,
                onQuality = vm::setQuality
            )

            Box(Modifier.weight(1f)) {
                if (tracks.isEmpty()) {
                    EmptyState { showAdd = true }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(tracks, key = { _, t -> t.id }) { _, track ->
                            TrackRow(
                                track = track,
                                isCurrent = ui.currentTrackId == track.id,
                                isLoading = track.id in resolving,
                                onPlay = { vm.play(track) },
                                onAdd = { showAdd = true },
                                onDelete = { vm.remove(track) }
                            )
                        }
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                NowPlayingBar(
                    vm = vm,
                    title = tracks.firstOrNull { it.id == ui.currentTrackId }?.title ?: "未播放",
                    ui = ui,
                    onExpand = { expandedPlayer = true }
                )
            }
        }
    }

    if (expandedPlayer) {
        FullPlayer(
            vm = vm,
            title = tracks.firstOrNull { it.id == ui.currentTrackId }?.title ?: "",
            ui = ui,
            formats = formats,
            onClose = { expandedPlayer = false }
        )
    }

    if (showAdd) {
        AddLinkDialog(
            onDismiss = { showAdd = false },
            onConfirm = { text ->
                showAdd = false
                vm.addLink(text)
            }
        )
    }

    if (showSettings) {
        SettingsDialog(vm = vm, onDismiss = { showSettings = false })
    }

    updateReport?.let { report ->
        UpdateReportDialog(text = report, onDismiss = { vm.dismissUpdateReport() })
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Headphones, contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("粘贴一个视频链接开始", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "解析后只播放流，不会下载到手机。\n息屏和退出 App 后音频继续播放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("添加链接")
        }
    }
}

@Composable
private fun ModeRow(
    playMode: PlayMode,
    quality: QualityCap,
    onMode: (PlayMode) -> Unit,
    onQuality: (QualityCap) -> Unit
) {
    var qualityMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = playMode == PlayMode.AUDIO_ONLY,
            onClick = { onMode(PlayMode.AUDIO_ONLY) },
            label = { Text("仅音频") },
            leadingIcon = { Icon(Icons.Default.Headphones, null, Modifier.size(18.dp)) }
        )
        FilterChip(
            selected = playMode == PlayMode.VIDEO,
            onClick = { onMode(PlayMode.VIDEO) },
            label = { Text("视频") },
            leadingIcon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) }
        )
        Box {
            AssistChip(
                onClick = { qualityMenu = true },
                enabled = playMode == PlayMode.VIDEO,
                label = { Text(quality.label) },
                leadingIcon = { Icon(Icons.Default.HighQuality, null, Modifier.size(18.dp)) }
            )
            DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                QualityCap.entries.forEach { cap ->
                    DropdownMenuItem(
                        text = { Text(cap.label) },
                        onClick = { qualityMenu = false; onQuality(cap) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isLoading: Boolean,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(72.dp, 48.dp)
                    .background(Color.Black, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (track.thumbnail != null) {
                    AsyncImage(
                        model = track.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    listOfNotNull(track.uploader, formatDuration(track.durationMs))
                        .joinToString(" · ")
                        .ifEmpty { hostOf(track.sourceUrl) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // The "+" the user asked for: add the next link right from any row.
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "再添加一个")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBar(
    vm: MainViewModel,
    title: String,
    ui: PlayerUiState,
    onExpand: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.clickable(onClick = onExpand)) {
            if (ui.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${formatDuration(ui.positionMs)} / ${formatDuration(ui.durationMs)}" +
                            (ui.qualityLabel?.let { "  ·  $it" } ?: "") +
                            "  ·  ${ui.speed}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.previous() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一个")
                }
                IconButton(onClick = { vm.togglePlay() }) {
                    if (ui.isBuffering) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放/暂停"
                        )
                    }
                }
                IconButton(onClick = { vm.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一个")
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FullPlayer(
    vm: MainViewModel,
    title: String,
    ui: PlayerUiState,
    formats: List<com.echo.audiolinkplayer.core.StreamFormat>,
    onClose: () -> Unit
) {
    var formatMenu by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("收起") } },
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ui.hasVideo) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setKeepContentOnPlayerReset(true)
                            }
                        },
                        update = { it.player = vm.player() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Slider(
                    value = scrub ?: (if (ui.durationMs > 0)
                        ui.positionMs.toFloat() / ui.durationMs else 0f).coerceIn(0f, 1f),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { vm.seekTo((it * ui.durationMs).toLong()) }
                        scrub = null
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(ui.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatDuration(ui.durationMs), style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.toggleShuffle() }) {
                        Icon(
                            Icons.Default.Shuffle, "随机",
                            tint = if (ui.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.previous() }) {
                        Icon(Icons.Default.SkipPrevious, "上一个")
                    }
                    IconButton(onClick = { vm.togglePlay() }) {
                        Icon(
                            if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "播放/暂停",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = { vm.next() }) {
                        Icon(Icons.Default.SkipNext, "下一个")
                    }
                    IconButton(onClick = { vm.cycleRepeat() }) {
                        Icon(
                            if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                            else Icons.Default.Repeat,
                            "循环",
                            tint = if (ui.repeatMode == Player.REPEAT_MODE_OFF)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    when (ui.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "单个循环"
                        Player.REPEAT_MODE_ALL -> "列表循环"
                        else -> "不循环"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                Text("倍速", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SPEEDS.forEach { s ->
                        FilterChip(
                            selected = kotlin.math.abs(ui.speed - s) < 0.01f,
                            onClick = { vm.setSpeed(s) },
                            label = { Text("${s}x", fontSize = 12.sp) }
                        )
                    }
                }

                if (formats.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Box {
                        OutlinedButton(onClick = { formatMenu = true }) {
                            Icon(Icons.Default.HighQuality, null)
                            Spacer(Modifier.width(6.dp))
                            Text("清晰度：${ui.qualityLabel ?: "自动"}")
                        }
                        DropdownMenu(formatMenu, onDismissRequest = { formatMenu = false }) {
                            formats.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.label}  ·  ${f.ext}") },
                                    onClick = {
                                        formatMenu = false
                                        vm.selectFormat(f)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember {
        mutableStateOf(clipboard.getText()?.text?.takeIf { it.startsWith("http") } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加链接") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("视频/音频页面链接") },
                    placeholder = { Text("https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { text = it }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "粘贴")
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "支持一次粘贴多条（换行分隔）。播放列表链接会整个展开。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("解析") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cacheMb by remember { mutableStateOf(Settings.cacheMb.toFloat()) }
    var autoUpdate by remember { mutableStateOf(Settings.autoUpdate) }
    var nightly by remember { mutableStateOf(Settings.nightlyEngine) }
    var proxy by remember { mutableStateOf(Settings.proxySpec) }
    var engineUrl by remember { mutableStateOf(Settings.customEngineUrlValue) }
    var showLog by remember { mutableStateOf(false) }
    var webFallback by remember { mutableStateOf(Settings.webFallbackEnabled) }
    var webViewUa by remember { mutableStateOf(Settings.useWebViewUa) }
    val engineVersion by vm.engineVersion.collectAsState()
    val domains = remember { vm.cookieDomains() }

    if (showLog) {
        LogDialog(text = vm.diagnostics(), onDismiss = { showLog = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                Text("网站登录", style = MaterialTheme.typography.titleSmall)
                Text(
                    "有些站点要登录后才能取到视频。用内置浏览器正常登录一次即可，" +
                        "登录信息只保存在本机。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, LoginActivity::class.java))
                    }) { Text("打开浏览器登录") }
                    OutlinedButton(onClick = { vm.clearCookies() }) { Text("清除登录") }
                }
                if (domains.isNotEmpty()) {
                    Text(
                        "已保存：" + domains.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilterChip(
                    selected = webViewUa,
                    onClick = {
                        webViewUa = !webViewUa
                        Settings.useWebViewUa = webViewUa
                    },
                    label = { Text("解析时沿用浏览器身份") }
                )
                Text(
                    "默认关闭。yt-dlp 自带的身份是经过测试的，强行换成手机浏览器的" +
                        "反而容易被判成机器人。只有某站的 Cookie 认浏览器时才需要打开。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("缓存上限：${cacheMb.toInt()} MB", style = MaterialTheme.typography.titleSmall)
                Text(
                    "只用于回放/拖动，放在系统缓存目录，不算下载。设为 0 完全关闭。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = cacheMb,
                    onValueChange = { cacheMb = it },
                    onValueChangeFinished = { Settings.cacheMb = cacheMb.toInt() },
                    valueRange = 0f..1024f,
                    steps = 15
                )
                OutlinedButton(onClick = { vm.clearCache() }) { Text("清空缓存") }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("浏览器解析（备用）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "有些网站（PornHub 是典型）会识别 TLS 指纹，专挑 yt-dlp 拦，" +
                        "返回 403。打开这个后，yt-dlp 失败时会改用系统 WebView 加载页面，" +
                        "从真实浏览器请求里嗅探播放地址。慢一些（十几秒），但能过。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = webFallback,
                    onClick = {
                        webFallback = !webFallback
                        Settings.webFallbackEnabled = webFallback
                    },
                    label = { Text(if (webFallback) "已开启" else "已关闭") }
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("解析引擎", style = MaterialTheme.typography.titleSmall)
                Text(
                    "内核是 yt-dlp。它比 App 本身重要得多：站点改版后，" +
                        "旧引擎会直接被拒 (403)，更新一下基本就好了。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本：" + (engineVersion ?: "读取中…") +
                        if (engineVersion != null && vm.engineIsStale()) "   已过期" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (engineVersion != null && vm.engineIsStale())
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.updateEngine() }) { Text("立即更新") }
                    FilterChip(
                        selected = autoUpdate,
                        onClick = {
                            autoUpdate = !autoUpdate
                            Settings.autoUpdate = autoUpdate
                        },
                        label = { Text("自动更新") }
                    )
                    FilterChip(
                        selected = nightly,
                        onClick = {
                            nightly = !nightly
                            Settings.nightlyEngine = nightly
                        },
                        label = { Text("每日构建") }
                    )
                }
                OutlinedTextField(
                    value = engineUrl,
                    onValueChange = { engineUrl = it; Settings.customEngineUrlValue = it },
                    label = { Text("自定义更新地址（可留空）") },
                    placeholder = { Text("https://…/yt-dlp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("代理", style = MaterialTheme.typography.titleSmall)
                Text(
                    "如果你的梯子是「按应用分流」而没放行本 App，或者用的是本地端口代理，" +
                        "这里填上；解析和播放都会走它。留空表示不走代理。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = proxy,
                    onValueChange = { proxy = it },
                    label = { Text("代理地址") },
                    placeholder = { Text("http://127.0.0.1:7890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { vm.setProxy(proxy) }) { Text("保存代理设置") }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLog = true }) { Text("查看日志") }
                    TextButton(onClick = { vm.clearAll() }) { Text("清空播放列表") }
                }
            }
        }
    )
}

@Composable
private fun LogDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun UpdateReportDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新解析引擎") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/** Nudges the user toward the one fix that resolves most extraction failures. */
@Composable
private fun StaleEngineBanner(onUpdate: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "解析引擎已过期，很多网站会直接返回 403",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUpdate) { Text("更新") }
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

private fun hostOf(url: String): String =
    runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
