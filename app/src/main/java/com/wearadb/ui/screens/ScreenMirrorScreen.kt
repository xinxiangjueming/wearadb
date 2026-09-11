package com.wearadb.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.adb.MirrorStatus
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding
import kotlinx.coroutines.launch

/**
 * B1 有线投屏页：USB 通道 + scrcpy-server + MediaCodec 解码到 SurfaceView。
 *
 * 生命周期契约：
 *  - SurfaceView.surfaceCreated → viewModel.startMirror(surface)（自动起播）
 *  - SurfaceView.surfaceDestroyed / 离开页面 → viewModel.stopMirror()（自动清理，
 *    关流解锁读循环并杀掉设备端 scrcpy-server）
 *
 * 触摸回控：页面坐标 → 编码坐标系（letterbox 居中 + scale）→ 设备真实分辨率，
 * 复用 viewModel.tap/swipe（内部已按 USB 通道路由 input tap/swipe）。
 */
@Composable
fun ScreenMirrorScreen(
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val status by viewModel.mirrorStatus.collectAsState()
    val encSize by viewModel.mirrorVideoSize.collectAsState()
    val realSize by viewModel.mirrorRealSize.collectAsState()
    val maxSize by viewModel.mirrorMaxSize.collectAsState()
    val bitRate by viewModel.mirrorBitRate.collectAsState()
    val maxFps by viewModel.mirrorMaxFps.collectAsState()
    val readOnly by viewModel.mirrorReadOnly.collectAsState()
    val turnOffScreen by viewModel.mirrorTurnOffScreen.collectAsState()
    val stayAwake by viewModel.mirrorStayAwake.collectAsState()

    // Surface 生命周期锚点
    var currentSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }

    // 触摸映射输入
    var viewSize by remember { mutableStateOf<IntSize?>(null) }
    var downPos by remember { mutableStateOf(Offset.Zero) }
    var lastPos by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableStateOf(0f) }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hPadding = adaptiveHorizontalPadding()
    val cardShape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius)

    /** 页面坐标 → 设备真实坐标（letterbox 居中换算），超出画面区域返回 null。 */
    fun mapToDevice(viewPos: Offset): Pair<Int, Int>? {
        val vs = viewSize ?: return null
        val enc = encSize ?: return null
        val real = realSize ?: return null
        if (enc.first <= 0 || enc.second <= 0 || real.first <= 0 || real.second <= 0) return null
        val scale = minOf(vs.width.toFloat() / enc.first, vs.height.toFloat() / enc.second)
        if (scale <= 0f) return null
        val dispW = enc.first * scale
        val dispH = enc.second * scale
        val ox = (vs.width - dispW) / 2f
        val oy = (vs.height - dispH) / 2f
        val ex = (viewPos.x - ox) / scale
        val ey = (viewPos.y - oy) / scale
        if (ex < 0f || ey < 0f || ex > enc.first || ey > enc.second) return null
        val rx = (ex / enc.first * real.first).toInt().coerceIn(0, real.first - 1)
        val ry = (ey / enc.second * real.second).toInt().coerceIn(0, real.second - 1)
        return rx to ry
    }

    fun tryStart() {
        val surface = currentSurface ?: return
        scope.launch { viewModel.startMirror(surface) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(statusBarPad))

        // ── 顶栏 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text(s.mirrorTitle, style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Spacer(Modifier.weight(1f))
            when (status) {
                is MirrorStatus.Streaming -> StatusChip(s.mirrorStatusStreaming, c.accent)
                is MirrorStatus.Starting -> StatusChip(s.mirrorStatusStarting, c.warning)
                is MirrorStatus.Error -> StatusChip((status as MirrorStatus.Error).message, c.error)
                MirrorStatus.Idle -> StatusChip(s.mirrorStatusIdle, c.onSurfaceVariant)
            }
        }

        // ── 画面区 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(cardShape)
                .background(Color.Black, cardShape)
                .border(1.dp, c.outlineVariant, cardShape)
                .onSizeChanged { viewSize = it }
                .pointerInput(readOnly) {
                    // 只读模式禁用触摸回控注入
                    if (!readOnly) detectDragGestures(
                        onDragStart = { off ->
                            downPos = off
                            lastPos = off
                            dragDistance = 0f
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragDistance += (change.position - change.previousPosition).getDistance()
                            lastPos = change.position
                        },
                        onDragEnd = {
                            if (dragDistance < 12f) {
                                mapToDevice(downPos)?.let { (x, y) -> viewModel.tap(x, y) }
                            } else {
                                val p1 = mapToDevice(downPos)
                                val p2 = mapToDevice(lastPos)
                                if (p1 != null && p2 != null) {
                                    viewModel.swipe(p1.first, p1.second, p2.first, p2.second)
                                }
                            }
                        }
                    )
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                currentSurface = holder.surface
                                surfaceReady = true
                                tryStart()
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceReady = false
                                currentSurface = null
                                viewModel.stopMirror()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── 非流态遮罩 ──
            when (val st = status) {
                is MirrorStatus.Starting -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(s.mirrorStatusStarting, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is MirrorStatus.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            st.message,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.stopMirror(); tryStart() }) {
                            Text(s.mirrorRetry, color = c.accent)
                        }
                    }
                }
                MirrorStatus.Idle -> {
                    Text(
                        s.mirrorStatusIdle,
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MirrorStatus.Streaming -> Unit
            }

            // ── 触摸提示（流态且非只读时底部半透明条） ──
            if (status is MirrorStatus.Streaming && encSize != null && !readOnly) {
                Text(
                    s.mirrorTouchHint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // ── 分辨率信息 ──
        if (encSize != null && realSize != null) {
            val enc = encSize!!
            val real = realSize!!
            Text(
                s.mirrorResolution("${enc.first}x${enc.second}", "${real.first}x${real.second}"),
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant
            )
        }

        // ── 画质/帧率选项（变更即用新参数无缝重启会话） ──
        MirrorOptionRow(
            label = s.mirrorOptionSize,
            options = listOf(
                s.mirrorAuto to 0,
                "720" to 720,
                "480" to 480,
                "360" to 360
            ),
            selected = maxSize
        ) { viewModel.setMirrorMaxSize(it) }

        MirrorOptionRow(
            label = s.mirrorOptionBitrate,
            options = listOf(
                "1M" to 1_000_000,
                "2M" to 2_000_000,
                "4M" to 4_000_000,
                "8M" to 8_000_000,
                "16M" to 16_000_000
            ),
            selected = bitRate
        ) { viewModel.setMirrorBitRate(it) }

        MirrorOptionRow(
            label = s.mirrorOptionFps,
            options = listOf(
                s.mirrorAuto to 0f,
                "10" to 10f,
                "15" to 15f,
                "24" to 24f,
                "30" to 30f,
                "60" to 60f
            ),
            selected = maxFps
        ) { viewModel.setMirrorMaxFps(it) }

        // ── 会话开关（只读 / 熄屏 / 保持唤醒；变更即用新参数无缝重启会话） ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                s.mirrorOptionToggles,
                style = MaterialTheme.typography.labelMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = readOnly,
                    onClick = { viewModel.setMirrorReadOnly(!readOnly) },
                    label = { Text(s.mirrorOptionReadonly, style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = turnOffScreen,
                    onClick = { viewModel.setMirrorTurnOffScreen(!turnOffScreen) },
                    label = { Text(s.mirrorOptionScreenOff, style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = stayAwake,
                    onClick = { viewModel.setMirrorStayAwake(!stayAwake) },
                    label = { Text(s.mirrorOptionStayAwake, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // ── 常用按键（只读模式下禁用注入） ──
        val keysEnabled = status is MirrorStatus.Streaming && !readOnly
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            MirrorKeyButton(Icons.Outlined.Home, s.mirrorKeyHome, keysEnabled) { viewModel.keyEvent(3) }
            MirrorKeyButton(Icons.AutoMirrored.Outlined.ArrowBack, s.mirrorKeyBack, keysEnabled) { viewModel.keyEvent(4) }
            MirrorKeyButton(Icons.Outlined.Layers, s.mirrorKeyRecents, keysEnabled) { viewModel.keyEvent(187) }
            MirrorKeyButton(Icons.Outlined.PowerSettingsNew, s.mirrorKeyPower, keysEnabled) { viewModel.keyEvent(26) }
            MirrorKeyButton(Icons.AutoMirrored.Outlined.VolumeUp, s.mirrorKeyVolUp, keysEnabled) { viewModel.keyEvent(24) }
            MirrorKeyButton(Icons.AutoMirrored.Outlined.VolumeDown, s.mirrorKeyVolDown, keysEnabled) { viewModel.keyEvent(25) }
        }

        // ── 控制区 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (status is MirrorStatus.Streaming || status is MirrorStatus.Starting) {
                Button(
                    onClick = { viewModel.stopMirror() },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(WearAdbTheme.shape.cornerRadius)),
                    colors = ButtonDefaults.buttonColors(containerColor = c.error)
                ) {
                    Text(s.mirrorStop)
                }
            } else if (surfaceReady) {
                Button(
                    onClick = { tryStart() },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(WearAdbTheme.shape.cornerRadius))
                ) {
                    Text(s.mirrorRetry)
                }
            }
        }

        Spacer(Modifier.height(navBarPad))
    }

    // 离开页面兜底清理（surfaceDestroyed 正常触发时为幂等操作）
    DisposableEffect(Unit) {
        onDispose { viewModel.stopMirror() }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1
    )
}

/** 单行画质选项：固定标签 + 横向可滚 FilterChip 组。 */
@Composable
private fun <T> MirrorOptionRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = WearAdbTheme.colors.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (text, value) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

/** 常用按键按钮：纯图标（keycode 注释见调用处），disabled 时置灰。 */
@Composable
private fun MirrorKeyButton(icon: ImageVector, contentDesc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            contentDescription = contentDesc,
            tint = if (enabled) WearAdbTheme.colors.onBackground else WearAdbTheme.colors.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}
