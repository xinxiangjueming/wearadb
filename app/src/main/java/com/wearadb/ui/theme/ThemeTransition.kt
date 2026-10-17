package com.wearadb.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 深浅色切换圆形揭露过渡（PowerMeter ThemeTransition.kt 移植，2026-10-03；
 * 用户指定：从屏幕右下角边框开始展开，过渡到整个界面）。
 *
 * 机制（纯应用内自绘，不触碰 configuration / 不 recreate）：
 * 1. [capture]（[WearAdbTheme] 闸门，系统深浅色切换路径）在真正生效前先 PixelCopy
 *    截取当前窗口像素（旧主题界面）；
 * 2. 截图完成后写入 [snapshot]——同批状态变更、同帧重组：下层界面已是新主题，顶层
 *    叠加「旧快照 + 右下角圆孔」覆盖层，首帧观感与切换前完全一致（旧快照全覆盖），无跳变；
 * 3. 圆孔半径 0 → 屏幕对角线（圆心=右下角顶点，最远点=左上角，400ms 匀速扩散）：
 *    孔外旧快照，孔内直接透出真实新界面——被扫过的区域颜色立即变为目标颜色；
 *    圆孔边缘经 BlurMaskFilter 羽化（FEATHER_PX 带宽），新旧两色柔和渐变不硬切。
 *    孔径扫满全屏即清除快照层，无收尾淡出阶段。
 * PixelCopy 失败（极少见）→ 直接切换，无动画降级。
 *
 * 与 PowerMeter 原版的差异：wearadb 为**单 Activity** 应用，原版为半透明二级页服务的
 * 退场交棒（noteExitTheme/consumeExitTheme）与静默路径（requestSilent/consumeSilent）、
 * capture 的 delayMs 参数均无使用场景，未移植；窗口归属保护（[ownerHost]）保留——
 * Activity 意外重建后残留快照不会被新窗口重播，且会被下次 capture 就地回收。
 */
object ThemeTransition {

    /** 切换前的旧主题全窗快照；非 null 时 [ThemeTransitionOverlay] 播放圆孔擦除动画 */
    var snapshot by mutableStateOf(null as ImageBitmap?)
        private set

    /** 当前快照持有者标记：[clear] 只在快照仍是传入项时生效。
     *  快照可能被多个 composition 的 overlay 同时播放，必须防止某个 overlay 先播完
     *  把快照清掉、其余 overlay 提前消失导致擦除动画半途中断。 */
    private var owner: ImageBitmap? = null

    /** 快照归属的**宿主窗口**：overlay 只播「本窗口自己截的」快照。
     *  防的是宿主 Activity 意外重建（动画未播完原窗口销毁 → 组合销毁、clear 从未执行）时，
     *  残留快照被新窗口重播一遍（先闪一张旧主题图、再从右下角滑开）。见 [isOwnedBy]。 */
    private var ownerHost: Activity? = null

    /** 动画播完由覆盖层清除快照（顶层 composable 无法访问 private setter，走此入口）；
     *  仅当快照仍是传入的 [snap] 时清除（幂等，多 overlay 并发安全） */
    fun clear(snap: ImageBitmap?) {
        if (owner != null && owner === snap) {
            snapshot = null
            owner = null
            ownerHost = null
        }
    }

    /** 快照是否由 [activity] 这个窗口截取（overlay 只播本窗口自己的快照，见 [ownerHost]） */
    fun isOwnedBy(activity: Activity?): Boolean = ownerHost != null && ownerHost === activity

    /**
     * 截取当前窗口像素（旧主题界面）并登记为过渡快照（[WearAdbTheme] 闸门的
     * 系统深浅色切换路径，零延迟——系统切换面板是 SystemUI 窗口，不在本 app 窗口内）。
     * @return true = 快照已就绪（含「已有快照在播」的幂等场景，直接复用不打断）；
     *         false = 截图失败（窗口未就绪 / PixelCopy 失败），调用方直接切换、无动画降级
     */
    suspend fun capture(activity: Activity): Boolean {
        // 已有快照在播（动画进行中又发生新的深浅切换）：复用现快照，不重复截、不打断。
        // 例外：归属宿主已结束（窗口销毁 → 组合销毁、clear 从未执行）→ 残留快照无人回收，
        // 就地丢弃后重截。
        if (snapshot != null) {
            val host = ownerHost
            if (host != null && (host === activity || (!host.isFinishing && !host.isDestroyed))) {
                return true
            }
            snapshot = null
            owner = null
            ownerHost = null
        }
        val decor = activity.window.decorView
        if (decor.width == 0 || decor.height == 0) return false
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { cont ->
            PixelCopy.request(
                activity.window, bitmap,
                { result -> cont.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        }
        if (!copied) return false
        val snap = bitmap.asImageBitmap()
        owner = snap
        ownerHost = activity
        snapshot = snap
        return true
    }

    /** 从 Compose 上下文向上找到宿主 Activity（WearAdbTheme 闸门使用） */
    fun activityOf(context: Context): Activity? = context.findActivity()

    /**
     * 宿主是否处于「可播动画」状态：只有**已 RESUMED 的页**才播圆孔揭露。
     *
     * 闸门在目标深浅变化后的首帧才触发，彼时宿主必已走完 onResume —— 从桌面/多任务/
     * 息屏回前台的「后台错过的变化」由此自然放行播动画（回前台瞬间窗口表面停留的旧主题帧
     * 正是揭露的起点画面）。
     */
    fun canAnimateFrom(activity: Activity?): Boolean = isHostForeground(activity)

    /** 宿主是否已在前台（RESUMED）且未在关闭中 */
    fun isHostForeground(activity: Activity?): Boolean {
        val owner = activity as? LifecycleOwner ?: return false
        return !activity.isFinishing &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}

// ==================== 过渡参数（2026-10-17 SportLink 用户定稿：匀速 400ms） ====================
/** 总时长 ms */
private const val TRANSITION_DURATION_MS = 400
/** 圆孔边缘羽化带宽 px：被扫过的区域立即显示新界面颜色，边缘新旧两色柔和渐变 */
private const val FEATHER_PX = 48f
/**
 * 孔径曲线：匀速（LinearEasing），总时长 400ms。
 * SportLink 2026-10-17 三轮校准史：easeIn(0.42,0,1,1) 前慢后快 → LinearOutSlowIn 开局太快
 * → 最终定稿匀速 400ms，无起步段、无减速段。本项目沿用该定稿参数。
 */
private val REVEAL_EASING = LinearEasing

/**
 * 主题切换过渡覆盖层：孔外=旧界面快照，孔内=直接透出真实新界面（被扫过的区域
 * 颜色立即变为目标颜色，不垫纯色、无收尾淡出）；圆孔边缘经 [FEATHER_PX] 羽化模糊，
 * 新旧两色柔和渐变。已内置在 [WearAdbTheme] 内、content 之上最后声明（勿在业务页面
 * 重复声明）。动画期间拦截全部触摸，扫满全屏后自动从组合中移除。
 */
@Composable
fun ThemeTransitionOverlay() {
    // 只播**本窗口自己截的**快照：宿主窗口销毁后的残留快照不在新窗口重播。
    // 归属不符 → 不渲染，交由 capture 的回收路径处理。
    val host = ThemeTransition.activityOf(LocalContext.current)
    val snap = ThemeTransition.snapshot?.takeIf { ThemeTransition.isOwnedBy(host) } ?: return
    val progress = remember(snap) { Animatable(0f) }
    // 旧快照 shader + 羽化 blur paint 只随快照构建一次（每帧只换 path）
    val snapshotPaint = remember(snap) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(
                snap.asAndroidBitmap(),
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
            maskFilter = android.graphics.BlurMaskFilter(
                FEATHER_PX,
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
    }
    LaunchedEffect(snap) {
        progress.animateTo(1f, tween(durationMillis = TRANSITION_DURATION_MS, easing = REVEAL_EASING))
        ThemeTransition.clear(snap)
    }
    // 宿主组合销毁（Activity 结束 / 动画未播完窗口销毁）→ 就地回收快照：进度动画随组合
    // 取消时上面的 clear 永远不会执行，残留快照会被后续窗口重播
    DisposableEffect(snap) {
        onDispose { ThemeTransition.clear(snap) }
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(snap) {
                // 动画期间吞掉所有触摸，避免误操作；覆盖层移除时协程随之取消
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // 圆心=右下角顶点，最远可见点=左上角；留 5% 余量补 blur 内缩的圆边（175px > FEATHER_PX）
        val maxR = sqrt(w * w + h * h) * 1.05f
        val r = maxR * progress.value
        // ⚠️ 外框矩形必须放大到屏幕外（margin ≥ 2×blur）：BlurMaskFilter 作用于 path 的
        // 所有边缘——矩形边若贴着屏幕边界，blur 会向屏幕内扩散，四周出现一圈旧色羽化
        // 残留直到动画结束。放大后矩形边的模糊落在屏幕外，只有圆孔边缘在屏幕内羽化。
        // 屏幕外区域由 CLAMP shader 采样快照边缘像素，颜色正确。
        val margin = FEATHER_PX * 2f
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-margin, -margin, w + margin, h + margin))
            addOval(Rect(center = Offset(w, h), radius = r))
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(outside.asAndroidPath(), snapshotPaint)
        }
    }
}
