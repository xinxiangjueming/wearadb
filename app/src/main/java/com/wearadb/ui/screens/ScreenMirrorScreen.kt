package com.wearadb.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.adb.MirrorStatus
import com.wearadb.adb.ScrcpyControlProtocol
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding
import kotlinx.coroutines.launch

/**
 * 触摸 MOVE 注入的最小间隔（ms）。约 60Hz，与 USB 总线流控节奏匹配；
 * 抬手时会补发最终位置，因此不会丢失手势终点。
 */
private const val MOVE_THROTTLE_MS = 16L

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

    // 画质/开关等低频设置默认收起，把纵向空间尽量让给画面区。
    // 原先「分辨率信息 + 3 行画质选项 + 1 行开关」常驻，连同 10dp 行距合计约 200dp，
    // 在竖屏手机上把画面区压得只剩一半左右高度。
    var optionsExpanded by remember { mutableStateOf(false) }

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

    /**
     * 手势期间复用同一个 pointerId，设备侧才能把它识别为同一次触摸。
     *
     * 必须用 scrcpy 官方约定的「通用手指」哨兵值 -1（UINT64_MAX 的补码表示，
     * 即 sc_pointer_id_generic_finger）。此前这里误用 MotionEvent 的
     * ACTION_POINTER_INDEX_MASK（0xFF00 = 65280）当作 pointerId，服务端会把它
     * 当成"多指手势中的第 65280 号手指"，DOWN/MOVE/UP 序列语义异常。
     */
    val pointerId = remember { ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER }

    fun tryStart() {
        val surface = currentSurface ?: return
        scope.launch { viewModel.startMirror(surface) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
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
        // 按**视频比例**约束自身尺寸（不拉伸）：SurfaceView 默认会把解码 buffer 缩放
        // 填满自身边界，容器比例 ≠ 视频比例时画面就会变形（手表 464x464 会被拉成
        // "矮胖"）。等比之后 mapToDevice 的 letterbox 偏移恒为 0，触摸映射也更精确。
        // Column 已设 CenterHorizontally，比例不匹配时在剩余空间内水平居中。
        val areaAspect: Float? =
            encSize?.takeIf { it.second > 0 }?.let { it.first.toFloat() / it.second }
                ?: realSize?.takeIf { it.second > 0 }?.let { it.first.toFloat() / it.second }

        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (areaAspect != null) Modifier.aspectRatio(areaAspect)
                    else Modifier.fillMaxWidth()
                )
                .clip(cardShape)
                .background(Color.Black, cardShape)
                .border(1.dp, c.outlineVariant, cardShape)
                .onSizeChanged { viewSize = it }
                .pointerInput(readOnly) {
                    // 只读模式禁用触摸回控注入。
                    // 用 awaitPointerEventScope 而不是 detectDragGestures：后者只在
                    // onDragEnd 回调，拖动全程无法注入（旧实现的"手势要等抬手 + 固定
                    // 300ms swipe"即源于此）。这里按下即注入 DOWN、移动逐帧注入 MOVE、
                    // 抬手注入 UP，设备侧表现为实时跟手的原生手势。
                    if (!readOnly) awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent(PointerEventPass.Main)
                            val change = down.changes.firstOrNull { it.pressed } ?: continue
                            if (down.type != PointerEventType.Press) continue

                            var start: Pair<Int, Int>? = mapToDevice(change.position)
                            if (start == null) continue // 起手落在黑边上，整个手势放弃
                            var lastPos = change.position
                            var canceled = false
                            var up = false
                            // MOVE 节流：触摸屏可到 120Hz，而 USB 总线上每条 control
                            // 消息都要过一次流控（等设备 OKAY）。逐帧全发会让写队列
                            // 堆积、拖动反而变卡。这里限制到 ~60Hz，抬手时补发最终位置
                            // （见 Release 分支的 lastPos），既不丢终点也不压垮通道。
                            var lastMoveAt = 0L

                            viewModel.touchDown(start.first, start.second, pointerId)
                            change.consume()

                            while (!up) {
                                val ev = awaitPointerEvent(PointerEventPass.Main)
                                val c = ev.changes.firstOrNull { it.id == change.id } ?: break
                                when (ev.type) {
                                    PointerEventType.Move -> {
                                        val p = mapToDevice(c.position)
                                        if (p != null) {
                                            lastPos = c.position
                                            val now = System.currentTimeMillis()
                                            if (now - lastMoveAt >= MOVE_THROTTLE_MS) {
                                                lastMoveAt = now
                                                viewModel.touchMove(p.first, p.second, pointerId)
                                            }
                                        }
                                        c.consume()
                                    }
                                    PointerEventType.Release -> {
                                        val p = mapToDevice(c.position) ?: mapToDevice(lastPos)
                                        if (p != null) viewModel.touchUp(p.first, p.second, pointerId)
                                        else viewModel.touchCancel(pointerId)
                                        c.consume()
                                        up = true
                                    }
                                    else -> {
                                        // 被系统抢走（返回手势等）→ 必须补 CANCEL，
                                        // 否则设备侧会留着一根"按下去没松"的手指
                                        canceled = true
                                        up = true
                                    }
                                }
                            }
                            if (canceled) viewModel.touchCancel(pointerId)
                        }
                    }
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

        }

        // ── 设置开关 + 分辨率信息（合并为一行；收起时只占这一行） ──
        // 低频设置默认收起，点击右侧「设置」展开。这样常驻高度从
        // 「1 行分辨率 + 3 行画质 + 1 行开关 + 4 段行距」压到 1 行，画面区显著变大。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { optionsExpanded = !optionsExpanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val enc = encSize
            val real = realSize
            if (enc != null && real != null) {
                Text(
                    s.mirrorResolution("${enc.first}x${enc.second}", "${real.first}x${real.second}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                s.mirrorOptions,
                style = MaterialTheme.typography.labelSmall,
                color = c.accent
            )
            Icon(
                if (optionsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = s.mirrorOptions,
                tint = c.accent,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = optionsExpanded,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(220))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ── 画质/帧率选项（变更即用新参数无缝重启会话） ──
                // 默认 1024（与 ViewModel 的 _mirrorMaxSize 初始值一致）：手表长边普遍 ~466，
                // 1024 不损清晰度；大屏设备则避免按原生长边编码灌满 ADB 通道。
                MirrorOptionRow(
                    label = s.mirrorOptionSize,
                    options = listOf(
                        s.mirrorAuto to 0,
                        "1024" to 1024,
                        "720" to 720,
                        "480" to 480
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
            }
        }

        // ── 常用按键 + 控制按钮（合并为一行，省下一整行高度） ──
        // 按键组横向可滚：窄屏放不下 6 个键时自动可滑，不会挤掉右侧按钮。
        // 只读模式下禁用注入。
        val keysEnabled = status is MirrorStatus.Streaming && !readOnly
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MirrorKeyButton(Icons.Outlined.Home, s.mirrorKeyHome, keysEnabled) { viewModel.mirrorKeyEvent(3) }
                MirrorKeyButton(Icons.AutoMirrored.Outlined.ArrowBack, s.mirrorKeyBack, keysEnabled) { viewModel.mirrorKeyEvent(4) }
                MirrorKeyButton(Icons.Outlined.Layers, s.mirrorKeyRecents, keysEnabled) { viewModel.mirrorKeyEvent(187) }
                MirrorKeyButton(Icons.Outlined.PowerSettingsNew, s.mirrorKeyPower, keysEnabled) { viewModel.mirrorKeyEvent(26) }
                MirrorKeyButton(Icons.AutoMirrored.Outlined.VolumeUp, s.mirrorKeyVolUp, keysEnabled) { viewModel.mirrorKeyEvent(24) }
                MirrorKeyButton(Icons.AutoMirrored.Outlined.VolumeDown, s.mirrorKeyVolDown, keysEnabled) { viewModel.mirrorKeyEvent(25) }
            }
            if (status is MirrorStatus.Streaming || status is MirrorStatus.Starting) {
                Button(
                    onClick = { viewModel.stopMirror() },
                    modifier = Modifier.clip(RoundedCornerShape(WearAdbTheme.shape.cornerRadius)),
                    colors = ButtonDefaults.buttonColors(containerColor = c.error)
                ) {
                    Text(s.mirrorStop)
                }
            } else if (surfaceReady) {
                Button(
                    onClick = { tryStart() },
                    modifier = Modifier.clip(RoundedCornerShape(WearAdbTheme.shape.cornerRadius))
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
