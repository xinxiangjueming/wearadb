// 文件: com/wearadb/ui/navigation/RevealTransition.kt
// 一镜到底转场 —— sportlink ui/ClipReveal.kt 的单 Activity + NavHost 移植版（2026-10-03 v3）
//
// 交互（与 sportlink ClipReveal 同一套公式/时间窗，逐条对照）：
// 点击列表卡片 → 目标页被裁剪在**锚点卡片矩形**内出现，随后四边同时向外撑开
// 铺满全屏（裁剪而非缩放：内容全尺寸布局，永不形变）；卡片截图（GraphicsLayer
// 录制）钉在原位随进度淡出（f 0→0.22），目标页内容延迟淡入（f 0.48→0.88）；
// 容器底色 = 卡片表面色众数采样（展开窗口内部就是卡片表面，盖住下方旧列表）。
// 返回时反向收回：内容前 55% 淡出、截图 45% 后淡回，窗口收回卡片矩形后与真
// 卡片像素衔接。四角圆角从锚点卡片圆角插值到全局控件圆角（屏幕圆角同源）。
//
// 架构对应关系（sportlink → wearadb）：
// - ClipRevealLayout（View 层裁剪）→ RevealPage 的 drawWithContent + clipPath
// - Holder/ValueAnimator（自驱进度）→ NavHost 转场本身（AnimatedContentScope.transition）
//   进度 f：进场 580ms FastOutSlowIn（0→1）、收拢 420ms FastOutLinearIn（1→0），
//   NavHost 的 enter/exit 用 0 位移 slide 提供同参动画窗口，旧页静止保持可见
//   （= sportlink 半透明窗口达成的「旧页面在动画期间仍然可见」）。
// - 锚点截图浮层 → RevealOverlay（MainActivity 里画在 NavHost 之上）
// - 触摸阻断（TouchBlockingHost）→ RevealOverlay 动画期消费全部触摸
// - 返回键收拢 → 不需要 BackHandler：popBackStack 的 popExit 转场就是收拢动画
//
// 关键硬约束（sportlink 踩坑对照）：
// - 锚点矩形用**窗口坐标**（positionInWindow），换算页面坐标时相减，层级/insets 差全抵消；
// - 进度 f 与各透明度都是**纯函数**（进场/收拢/中途打断共用同一状态，永不跳变）；
// - 容器必须自身不透明（backdrop 铺卡片色），窗口长大时靠它盖住源列表；
// - 无锚点/截图失败 → 无会话降级：页面直接显示，无任何视觉异常。
@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.wearadb.ui.navigation

import android.graphics.Bitmap
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.wearadb.ui.theme.WearAdbTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

// ── 时间窗（照抄 sportlink ClipReveal）──────────────────────────────────────

/** 展开时长（ms），配 FastOutSlowInInterpolator（sportlink OPEN_MS=580） */
const val REVEAL_OPEN_MS = 580

/** 收拢时长（ms）（sportlink CLOSE_MS=420） */
const val REVEAL_CLOSE_MS = 420

/** FastOutLinearIn 曲线（compose core 未提供，Material 同参数） */
private val FastOutLinearInEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** 卡片截图淡出完成点（f）：0→0.22 快速淡出 */
private const val ANCHOR_FADE_END = 0.22f

/** 详情内容淡入窗口（f）：0.48→0.88，展开结束前就开始显示 */
private const val CONTENT_IN_START = 0.48f
private const val CONTENT_IN_END = 0.88f

/** 收拢侧时间窗（raw = 收拢已播比例）：内容在前 55% 淡出 */
private const val CONTENT_OUT_END = 0.55f

/** 收拢侧：列表文字（卡片截图）从 raw 45% 之后才淡回 */
private const val ANCHOR_IN_START = 0.45f

/** 进场侧截图透明度（f 的纯函数；收拢反向即淡回） */
internal fun anchorAlphaAt(f: Float): Float = 1f - (f / ANCHOR_FADE_END).coerceIn(0f, 1f)

/** 进场侧内容透明度（f 的纯函数；收拢反向即淡出） */
internal fun contentAlphaAt(f: Float): Float =
    ((f - CONTENT_IN_START) / (CONTENT_IN_END - CONTENT_IN_START)).coerceIn(0f, 1f)

/** 收拢侧截图淡入（raw 45%→100%，出现太早会突兀） */
internal fun anchorAlphaIn(raw: Float): Float =
    ((raw - ANCHOR_IN_START) / (1f - ANCHOR_IN_START)).coerceIn(0f, 1f)

/** 收拢侧内容淡出（raw 0→55%） */
internal fun contentAlphaOut(raw: Float): Float = 1f - (raw / CONTENT_OUT_END).coerceIn(0f, 1f)

/**
 * 一镜到底会话/锚点登记处。
 *
 * - [updateAnchor]：入口卡片经 [Modifier.revealAnchor] 持续上报窗口矩形 + 绘制图层；
 * - [capture]：点击瞬间消费锚点（截图 + 表面色众数采样）产出 [Session]；
 * - 会话按 route 存入 [sessions]（父页会话保留供其后的返回收拢），最新会话即
 *   [topSession] —— 只有顶层会话的页面播动画/写进度，避免双页写状态互相打架；
 * - 顶层会话页离开组合（返回收拢完成）时 [releaseTop] 释放，其下页面重新组合时
 *   认领自己的会话（转场静止后才认领，防止 popEnter 期间误播展开）。
 */
object RevealTransition {

    /** 锚点槽位：窗口矩形（值拷贝）+ 图层弱引用（组合离开后允许 GC，矩形保留） */
    internal class Anchor {
        var bounds: Rect = Rect.Zero
        var layerRef: WeakReference<GraphicsLayer>? = null
        var cornerRadiusPx: Float = 0f
    }

    /** 一次转场会话：锚点卡片截图（可空——截图失败仍做纯裁剪展开）+ 窗口矩形快照 + 表面色 */
    internal class Session(
        val key: String,
        val anchorRect: Rect,
        val bitmap: ImageBitmap?,
        val surfaceColor: Color,
        val anchorCornerRadiusPx: Float,
    )

    private val anchors = HashMap<String, Anchor>()

    /** 全部存活会话（key=route）。父页会话保留——返回到该页时收拢用 */
    internal val sessions = HashMap<String, Session>()

    /** 顶层会话：唯一播动画/写进度的页面；null = 无一镜到底在途 */
    internal val topSession = mutableStateOf<Session?>(null)

    /** 顶层转场进度 f ∈ [0,1]（0=锚点卡片矩形，1=全屏）。RevealPage 逐帧写入，overlay 读取 */
    val progressState = mutableFloatStateOf(1f)

    /** 锚点截图透明度（overlay 用，RevealPage 按方向算好写入） */
    val anchorAlphaState = mutableFloatStateOf(0f)

    /** 转场动画进行中（overlay 触摸阻断的开关） */
    val animatingState = mutableStateOf(false)

    internal fun updateAnchor(key: String, bounds: Rect, layer: GraphicsLayer, cornerRadiusPx: Float) {
        val a = anchors[key] ?: Anchor().also { anchors[key] = it }
        a.bounds = bounds
        a.layerRef = WeakReference(layer)
        a.cornerRadiusPx = cornerRadiusPx
    }

    /** 槽位当前矩形（收拢几何每帧现取；无槽位返回 null） */
    internal fun currentAnchorRect(key: String): Rect? =
        anchors[key]?.takeIf { !it.bounds.isEmpty }?.bounds

    /**
     * 消费锚点并截图。返回 null 仅当**锚点不存在/矩形非法**（无锚点 = 降级直切）；
     * 截图失败但锚点有效时仍产出会话（bitmap = null）→ 退化为纯裁剪展开
     * （sportlink `anchorBitmap = null` 的既有降级路径），动画不会整个消失。
     *
     * key 归一化：带参路由（如 `pairing?host=..&port=..`）的锚点登记在 base route 上，
     * 任一形式都能取到同一个槽位。
     */
    internal suspend fun capture(key: String): Session? {
        val base = key.substringBefore('?')
        val anchor = anchors[base] ?: anchors[key] ?: return null
        if (anchor.bounds.isEmpty) return null
        val rect = Rect(anchor.bounds.left, anchor.bounds.top, anchor.bounds.right, anchor.bounds.bottom)
        val cornerRadiusPx = anchor.cornerRadiusPx
        var bitmap: ImageBitmap? = null
        var surface = Color.Unspecified
        val layer = anchor.layerRef?.get()
        if (layer != null) {
            try {
                val bmp = layer.toImageBitmap()
                bitmap = bmp
                // HARDWARE 位图像素不可读：采样需要软件拷贝（绘制仍用原位图）
                val android = bmp.asAndroidBitmap()
                val readable = if (android.config == Bitmap.Config.HARDWARE) {
                    try {
                        android.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    android
                }
                surface = readable?.let {
                    withContext(Dispatchers.Default) { dominantOpaqueColor(it) }
                } ?: Color.Unspecified
            } catch (_: Throwable) {
                bitmap = null
            }
        }
        return Session(base, rect, bitmap, surface, cornerRadiusPx)
    }

    /** 登记顶层会话（截图淡入等价真卡片，导航切换被它无缝盖住） */
    internal fun beginSession(session: Session) {
        sessions[session.key] = session
        topSession.value = session
        progressState.floatValue = 0f
        anchorAlphaState.floatValue = 1f
        animatingState.value = true
    }

    /** 顶层会话页离开组合（返回收拢完成）→ 释放；被新导航覆盖的旧页不释放 */
    internal fun releaseTop(route: String) {
        val s = topSession.value ?: return
        if (s.key == route) {
            topSession.value = null
            animatingState.value = false
            anchorAlphaState.floatValue = 0f
        }
    }

    /**
     * 位图**不透明主色**（照抄 sportlink dominantOpaqueColor）：每通道高 5bit
     * 归桶取众数，返回该桶首次遇到的原色。卡片表面是纯色且占绝大多数面积，
     * 比单点采样稳（单点落在圆角外/异色元素会把错误底色带进整段转场）。
     */
    private fun dominantOpaqueColor(card: Bitmap): Color? {
        return try {
            val counts = HashMap<Int, Int>()
            val sample = HashMap<Int, Int>()
            val step = 8
            var y = 0
            while (y < card.height) {
                var x = 0
                while (x < card.width) {
                    val c = card.getPixel(x, y)
                    if (((c ushr 24) and 0xFF) >= 200) {
                        val key = (((c ushr 19) and 0x1F) shl 10) or
                            (((c ushr 11) and 0x1F) shl 5) or
                            ((c ushr 3) and 0x1F)
                        counts[key] = (counts[key] ?: 0) + 1
                        if (!sample.containsKey(key)) sample[key] = c
                    }
                    x += step
                }
                y += step
            }
            val bestKey = counts.maxByOrNull { it.value }?.key ?: return null
            Color(sample[bestKey]!!)
        } catch (_: Throwable) {
            null
        }
    }
}

// ── 源端锚点采集 ─────────────────────────────────────────────────────────────

/**
 * 挂在入口卡片上：持续上报窗口矩形 + 录制绘制图层（供点击瞬间截图）。
 * [key] 约定为**目标页 route**。[cornerRadiusDp] = 锚点卡片实际显示圆角。
 *
 * ⚠️ 摆放位置：必须在 `Modifier.clip(shape)` **之前**——record 只录它之后链段的
 * 绘制，放 clip 前才能把圆角裁剪一起录进去（圆角外透明，无页面底色污染）。
 */
fun Modifier.revealAnchor(key: String, cornerRadiusDp: Dp): Modifier = composed {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadiusDp.toPx() }
    this
        .drawWithContent {
            // 每帧把内容录进图层（sportlink ContainerSource 同款：record 后照常
            // drawContent 正常绘制屏幕显示；不用 drawLayer 重放）
            layer.record { this@drawWithContent.drawContent() }
            drawContent()
        }
        .onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            val size = coords.size
            RevealTransition.updateAnchor(
                key,
                Rect(pos.x, pos.y, pos.x + size.width, pos.y + size.height),
                layer,
                cornerRadiusPx,
            )
        }
}

/**
 * 一镜到底启动器：点击入口时先截图登记会话，再执行导航。
 * 截图失败/无锚点 → 直接导航（目标页无会话降级为直接显示）。
 */
@Composable
fun rememberRevealLauncher(): (key: String, navigate: () -> Unit) -> Unit {
    val scope: CoroutineScope = rememberCoroutineScope()
    return remember {
        { key: String, navigate: () -> Unit ->
            scope.launch {
                val session = RevealTransition.capture(key)
                if (session != null) RevealTransition.beginSession(session)
                navigate()
            }
        }
    }
}

// ── 目标页装配（NavGraph 各 destination 包一层）──────────────────────────────

/**
 * 一镜到底的目标页容器：进度由 NavHost 转场（[LocalNavTransitionScope] 的
 * transition）驱动——进场 f 0→1 播展开、返回时 popExit f 1→0 播收拢，返回键/
 * 返回手势/页面内返回按钮统一走 popBackStack，无需任何 BackHandler 拦截。
 *
 * 内部层次（对应 sportlink ClipRevealLayout）：
 *   裁剪窗口（clipPath 圆角矩形插值）
 *   └─ backdrop（卡片表面色，不透明——窗口长大时盖住下方旧列表）
 *       └─ 内容层（contentAlpha 延迟淡入）
 * 锚点截图由 [RevealOverlay] 画在本页之上（窗口外透出旧列表）。
 *
 * 无会话/非顶层页 = 纯透传（零视觉影响、零降级路径）。
 *
 * ⚠️ 硬约束（2026-10-03 实测回归）：`content()` 在函数体内**只能有一个调用点**。
 * 不能写成"非 active 时 `content(); return`"。Compose 的 slot table 按调用点分组，
 * 两个调用点 = 两组互不相干的 group → 每次 active 翻转都会把整棵内容子树**重挂载**
 * （remember 全丢、LaunchedEffect 重跑）。"发现设备 → 配对"路径上 active 进出各翻转
 * 一次，DiscoveryScreen 因此两次重跑 `startDiscovery()`（其中会
 * `_discoveredDevices.value = emptyList()`），表现为转场结束后设备列表"闪一下"。
 * ⇒ 下方只用 `active` 切换**数值/颜色**（clip 参数、alpha、底色是否有色），结构恒定。
 *
 * @param route 本页 base route（会话 key 匹配用；带参路由传去参 base route）
 */
@Composable
fun RevealPage(route: String, content: @Composable () -> Unit) {
    val scope = LocalNavTransitionScope.current

    // 顶层会话被释放（下层页返回收拢完成）后，持有自己会话的页面重新组合时认领。
    // 必须等本页转场静止（currentState==targetState）才认领：popEnter 期间 transition
    // 还在跑，提前认领会让 f 从 0 重播一次幻影展开。
    if (scope != null && RevealTransition.topSession.value == null) {
        val mine = RevealTransition.sessions[route]
        if (mine != null) {
            SideEffect {
                if (RevealTransition.topSession.value == null &&
                    scope.transition.currentState == scope.transition.targetState
                ) {
                    RevealTransition.topSession.value = mine
                }
            }
        }
    }

    val session = RevealTransition.topSession.value
    val active = scope != null && session != null &&
        (route == session.key || route.startsWith(session.key + "?"))

    // ── 转场数值（进度 f / 三层 alpha）─────────────────────────────────────
    // ⚠️ 这里的 if **只计算数值**，绝不改变下方 Box 的结构（content() 单调用点，见头注释）。
    // 非顶层会话页取恒等值：f=1（无裁剪）、内容 alpha=1（原样显示）、不写任何全局状态。
    val transition = if (active) scope?.transition else null
    var f = 1f
    var contentAlpha = 1f
    var anchorAlpha = 0f
    if (transition != null) {
        val isEnter = transition.targetState == EnterExitState.Visible
        val ff by transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(REVEAL_OPEN_MS, easing = FastOutSlowInEasing)
                } else {
                    tween(REVEAL_CLOSE_MS, easing = FastOutLinearInEasing)
                }
            },
            label = "revealProgress",
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
        f = ff

        // 透明度（纯函数 + 收拢侧独立时间窗取 max/min，中途打断不跳变——照抄 sportlink）
        contentAlpha = if (isEnter) contentAlphaAt(f)
        else minOf(contentAlphaAt(f), contentAlphaOut(1f - f))
        anchorAlpha = if (isEnter) anchorAlphaAt(f)
        else maxOf(anchorAlphaAt(f), anchorAlphaIn(1f - f))

        // 同步给 overlay（截图层 + 触摸阻断）。仅顶层会话页写入
        RevealTransition.progressState.floatValue = f
        RevealTransition.anchorAlphaState.floatValue = anchorAlpha
        RevealTransition.animatingState.value =
            transition.currentState != transition.targetState

        // 顶层会话页离开组合（返回收拢完成）→ 释放
        DisposableEffect(route) {
            onDispose { RevealTransition.releaseTop(route) }
        }
    }

    val density = LocalDensity.current
    val endCornerRadiusPx = with(density) { WearAdbTheme.shape.cornerRadius.toPx() }
    val pageBackground = WearAdbTheme.colors.background
    // 不透明底板色：截图采样成功 → 锚点卡片表面色（窗口长大时靠它盖住下方旧列表）；
    // 采样失败/非顶层会话页 → 回落页面底色（不该出现异色块）
    val sheetColor = if (session != null && session.surfaceColor != Color.Unspecified)
        session.surfaceColor else pageBackground
    val clipPath = remember { Path() }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> pageOrigin = coords.positionInWindow() }
            .drawWithContent {
                // 非顶层会话页 = 纯透传（零裁剪）。
                // 顶层页**不再对 f→1 做早退**：f=1 时窗口本就是"全屏 + 屏幕圆角"
                // （endCornerRadiusPx 与物理屏幕圆角同源），与直接绘制视觉等价；
                // 早退反而让最后一帧丢掉那点圆角、与前一帧裁剪结果不一致（方屏四角跳一帧）。
                if (transition == null || session == null) {
                    drawContent()
                    return@drawWithContent
                }
                // 收拢几何每帧向槽位现取（来源页 popEnter 重组后矩形已刷新；
                // 源页在覆盖期不可滚动，快照与现值常态一致，旋转场景自动纠偏）
                val rect = RevealTransition.currentAnchorRect(session.key) ?: session.anchorRect
                // 锚点窗口矩形 → 本页坐标（相减，层级/insets 差全抵消）+ 钳到页面内
                val anchorLeft = (rect.left - pageOrigin.x).coerceIn(0f, size.width)
                val anchorTop = (rect.top - pageOrigin.y).coerceIn(0f, size.height)
                val anchorRight = (rect.right - pageOrigin.x).coerceIn(0f, size.width)
                val anchorBottom = (rect.bottom - pageOrigin.y).coerceIn(0f, size.height)
                if (anchorRight <= anchorLeft || anchorBottom <= anchorTop) {
                    drawContent()
                    return@drawWithContent
                }
                // 几何公式（照抄 sportlink RevealGeometry.applyTo）：
                // 容器四边 + 圆角共用同一条缓动同步插值——窗口本身就是那块
                // 连续长大的圆角表面；内容永不形变、永不位移
                val k = 1f - f
                val left = anchorLeft * k
                val top = anchorTop * k
                val right = size.width - (size.width - anchorRight) * k
                val bottom = size.height - (size.height - anchorBottom) * k
                val startRadius = session.anchorCornerRadiusPx
                    .coerceAtMost(minOf(right - left, bottom - top) / 2f)
                val radius = (startRadius + (endCornerRadiusPx - startRadius) * f)
                    .coerceAtLeast(0f)
                    .coerceAtMost(minOf(right - left, bottom - top) / 2f)
                clipPath.reset()
                clipPath.addRoundRect(RoundRect(left, top, right, bottom, radius, radius))
                clipPath(clipPath) { this@drawWithContent.drawContent() }
            }
    ) {
        // 不透明底板（结构恒定：非顶层会话页填透明，保证 content() 调用点永不移动）。
        // 被 clip 一起裁剪——窗口长大时靠它盖住下方旧列表；稳态由内容层的页面底色盖住。
        Box(Modifier.matchParentSize().background(if (active) sheetColor else Color.Transparent))
        // 内容层：延迟淡入 / 收拢前段淡出（底板之上、overlay 截图之下）
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = contentAlpha }) {
            // 页面自身底色：随内容一起淡入，稳态 = WearAdbTheme.colors.background，
            // 与 MainActivity 外层底色一致 —— 消除"底板用锚点卡片色时 active 翻转
            // 整页底色在 卡片色 ↔ 背景色 之间跳变"的断点。
            Box(Modifier.matchParentSize().background(if (active) pageBackground else Color.Transparent))
            content()
        }
    }
}

// ── 覆盖层（MainActivity 里画在 NavHost 之上：锚点截图 + 动画期触摸阻断）──────

/**
 * 锚点卡片截图层：钉在锚点窗口位置（原尺寸原位置，只做透明度交叉、永不形变），
 * 画在整棵 NavHost 之上——进场随进度淡出、收拢后段淡回，首末帧与真卡片像素一致。
 * 无顶层会话时零组合。动画期间吞掉全部触摸（禁止点到未展开区域/穿透到旧列表）。
 */
@Composable
fun RevealOverlay() {
    val session = RevealTransition.topSession.value ?: return
    val animating = RevealTransition.animatingState.value
    val alpha = RevealTransition.anchorAlphaState.floatValue
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (animating) Modifier.revealBlockTouches() else Modifier)
    ) {
        val bitmap = session.bitmap
        if (bitmap != null && alpha > 0.001f) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            session.anchorRect.left.roundToInt(),
                            session.anchorRect.top.roundToInt(),
                        )
                    }
                    .requiredSize(
                        with(density) { session.anchorRect.width.toDp() },
                        with(density) { session.anchorRect.height.toDp() },
                    )
                    .graphicsLayer { this.alpha = alpha }
            )
        }
    }
}

/** 动画期间吞掉全部触摸（消费 down 后，下层 clickable 收不到后续手势） */
private fun Modifier.revealBlockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.all { !it.pressed }) break
        }
    }
}
