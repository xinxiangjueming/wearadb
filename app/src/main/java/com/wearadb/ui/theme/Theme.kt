package com.wearadb.ui.theme

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import com.wearadb.ui.utils.NavigationBarHelper


// ── Design Tokens ──

data class WearAdbColors(
    // Background
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceAlt: Color,

    // Text
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceDim: Color,
    val onSurfaceVariant: Color,

    // Accent
    val accent: Color,
    val accentDim: Color,
    val accentDark: Color,
    val onAccent: Color,

    // Status
    val error: Color,
    val onError: Color,
    val warning: Color,
    val info: Color,

    // Border / Outline
    val outline: Color,
    val outlineVariant: Color,
    val border: Color,

    // Toast / Snackbar 边框：深色模式浅白色，浅色模式浅黑色
    val toastBorder: Color,

    // Button
    val buttonPrimary: Color,
    val buttonPrimaryText: Color,
    val buttonSecondary: Color,
    val buttonSecondaryText: Color,
    val buttonDanger: Color,
    val buttonDangerText: Color,

    // Misc
    val statusDotActive: Color,
    val statusDotInactive: Color,
    val terminalCommand: Color,
    val terminalOutput: Color,
    val iconTint: Color,
    val chipSelected: Color,
    val chipSelectedText: Color,
    val chipDefault: Color,
    val chipDefaultText: Color,
    val fileIcon: Color,
    val folderIcon: Color,
    val systemAppDot: Color,
    val thirdPartyAppDot: Color,
    val disabledBadge: Color,

    // Input label
    val label: Color,

    // Selection state (file list, expandable items)
    val selectedBg: Color,
    val selectedBorder: Color,

    // Disabled button states
    val disabledButtonBg: Color,
    val disabledDangerBg: Color,
    val disabledDangerText: Color
)

data class WearAdbShape(
    val cornerRadius: Dp
)

// ── Light Theme Tokens ──

private val LightColors = WearAdbColors(
    background         = Gray120,
    surface            = Gray50,
    surfaceVariant     = Gray50,
    surfaceAlt         = Gray150,
    onBackground       = Gray900,
    onSurface          = Gray900,
    onSurfaceDim       = Gray500,
    onSurfaceVariant   = Gray500,
    accent             = Accent,
    accentDim          = AccentDim,
    accentDark         = AccentDark,
    onAccent           = Gray50,
    error              = DangerRed,
    onError            = Gray50,
    // warning 跟随深浅色：浅色模式用近黑(Gray900)，深色模式用近白(Gray100)。
    // 原 WarnYellow(0xFFFDE68A) 在双主题都固定为浅黄，不符合"跟随深浅色切换白/黑"的需求。
    warning            = Gray900,
    info               = InfoBlue,
    outline            = Gray300,
    outlineVariant     = Gray150,
    border             = Gray150,
    toastBorder        = Color(0x40000000),
    buttonPrimary      = AccentDark,
    buttonPrimaryText  = Gray50,
    buttonSecondary    = Gray150,
    buttonSecondaryText = Gray800,
    buttonDanger       = DangerRed,
    buttonDangerText   = Gray900,
    statusDotActive    = AccentDark,
    statusDotInactive  = Gray400,
    terminalCommand    = AccentDark,
    terminalOutput     = Gray900,
    iconTint           = Gray600,
    chipSelected       = AccentDark.copy(alpha = 0.15f),
    chipSelectedText   = AccentDark,
    chipDefault        = Gray150,
    chipDefaultText    = Gray600,
    fileIcon           = Gray500,
    folderIcon         = InfoBlue,
    systemAppDot       = InfoBlue,
    thirdPartyAppDot   = AccentDark,
    disabledBadge      = DangerRed,
    label              = Gray600,
    selectedBg         = Accent.copy(alpha = 0.08f),
    selectedBorder     = Accent.copy(alpha = 0.3f),
    disabledButtonBg   = Gray150.copy(alpha = 0.5f),
    disabledDangerBg   = DangerRed.copy(alpha = 0.3f),
    disabledDangerText = Gray50.copy(alpha = 0.5f)
)

// ── Dark Theme Tokens ──

private val DarkColors = WearAdbColors(
    background         = Gray950,
    surface            = Gray900,
    surfaceVariant     = Gray900,
    surfaceAlt         = Gray800,
    onBackground       = Gray100,
    onSurface          = Gray100,
    onSurfaceDim       = Gray400,
    onSurfaceVariant   = Gray400,
    accent             = Accent,
    accentDim          = AccentDim,
    accentDark         = AccentDark,
    onAccent           = Gray900,
    error              = ErrorRed,
    onError            = Gray900,
    // warning 跟随深浅色：浅色模式用近黑(Gray900)，深色模式用近白(Gray100)。
    warning            = Gray100,
    info               = InfoBlue,
    outline            = Gray700,
    outlineVariant     = Gray800,
    border             = Gray800,
    toastBorder        = Color(0x59FFFFFF),
    buttonPrimary      = Accent,
    buttonPrimaryText  = Gray900,
    buttonSecondary    = Gray800,
    buttonSecondaryText = Gray200,
    buttonDanger       = Color(0xFFB91C1C),
    buttonDangerText   = Gray100,
    statusDotActive    = Accent,
    statusDotInactive  = Gray600,
    terminalCommand    = Accent,
    terminalOutput     = Gray100,
    iconTint           = Gray400,
    chipSelected       = AccentDark.copy(alpha = 0.25f),
    chipSelectedText   = Accent,
    chipDefault        = Gray800,
    chipDefaultText    = Gray400,
    fileIcon           = Gray400,
    folderIcon         = InfoBlue,
    systemAppDot       = InfoBlue,
    thirdPartyAppDot   = Accent,
    disabledBadge      = ErrorRed,
    label              = Gray400,
    selectedBg         = Accent.copy(alpha = 0.12f),
    selectedBorder     = Accent.copy(alpha = 0.4f),
    disabledButtonBg   = Gray800.copy(alpha = 0.5f),
    disabledDangerBg   = ErrorRed.copy(alpha = 0.3f),
    disabledDangerText = Gray900.copy(alpha = 0.5f)
)

// ── CompositionLocals ──

val LocalWearAdbColors = staticCompositionLocalOf { LightColors }
val LocalWearAdbShape = staticCompositionLocalOf { WearAdbShape(cornerRadius = 28.dp) }

// ── Material ColorScheme Mapping ──

private fun WearAdbColors.toMaterialLight() = lightColorScheme(
    primary            = accentDark,
    onPrimary          = onAccent,
    primaryContainer   = accent.copy(alpha = 0.15f),
    onPrimaryContainer = accentDark,
    secondary          = Gray600,
    onSecondary        = Gray50,
    secondaryContainer = Gray150,
    onSecondaryContainer = Gray800,
    tertiary           = info,
    background         = background,
    onBackground       = onBackground,
    surface            = surface,
    onSurface          = onSurface,
    surfaceVariant     = surfaceAlt,
    onSurfaceVariant   = onSurfaceDim,
    outline            = outline,
    outlineVariant     = outlineVariant,
    error              = error,
    onError            = onError,
    inverseSurface     = Gray200,
    inverseOnSurface   = Gray800,
)

private fun WearAdbColors.toMaterialDark() = darkColorScheme(
    primary            = accent,
    onPrimary          = onAccent,
    primaryContainer   = accentDark.copy(alpha = 0.25f),
    onPrimaryContainer = accent,
    secondary          = Gray400,
    onSecondary        = Gray900,
    secondaryContainer = Gray800,
    onSecondaryContainer = Gray200,
    tertiary           = info,
    background         = background,
    onBackground       = onBackground,
    surface            = surface,
    onSurface          = onSurface,
    surfaceVariant     = surfaceAlt,
    onSurfaceVariant   = onSurfaceDim,
    outline            = outline,
    outlineVariant     = outlineVariant,
    error              = error,
    onError            = onError,
    inverseSurface     = Gray800,
    inverseOnSurface   = Gray100,
)

// ── Theme Composable ──

object WearAdbTheme {
    val colors: WearAdbColors
        @Composable @ReadOnlyComposable
        get() = LocalWearAdbColors.current

    val shape: WearAdbShape
        @Composable @ReadOnlyComposable
        get() = LocalWearAdbShape.current
}

/**
 * 应用主题。
 *
 * **主题切换闸门**（对齐 PowerMeter PowerMeterTheme / Now in Android 模式）：
 * 「目标深浅」[darkTheme] 与「已生效深浅」[appliedDark] 分离——目标变化时先 PixelCopy
 * 截旧界面快照再放行，消除系统深浅色切换的硬切；覆盖层内置于 Box 顶层，全部页面自动获得
 * 「右下角圆孔扩散露出新主题」动画（见 [ThemeTransition]）。首次组合 appliedDark 即终值，
 * 无动画；截图失败无动画直接切（降级不劣于现状）。
 */
@Composable
fun WearAdbTheme(
    // 默认读系统；MainActivity 实际传入 darkThemeState（onCreate 初值 /
    // onConfigurationChanged / onResume 兜底三处更新——configChanges 含 uiMode 后，
    // 后台切换深浅时 ViewRootImpl 不分发配置，直读系统值不可依赖）。
    // 经下方闸门延迟生效，全部渲染取材见 appliedDark。
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // ── 主题切换闸门 ──────────────────────────────────────────────────────
    // 目标深浅变化时先截旧界面快照再放行；全部渲染取材以 appliedDark 为准。
    var appliedDark by remember { mutableStateOf(darkTheme) }
    val view = LocalView.current

    LaunchedEffect(darkTheme) {
        if (appliedDark == darkTheme) return@LaunchedEffect
        // 已有快照在播（动画未播完又发生新切换）：直接放行，overlay 继续播
        if (ThemeTransition.snapshot != null) { appliedDark = darkTheme; return@LaunchedEffect }
        // 宿主未 RESUMED：不截图、不播动画，直接放行
        val host = ThemeTransition.activityOf(view.context)
        if (host == null || !ThemeTransition.canAnimateFrom(host)) {
            appliedDark = darkTheme
            return@LaunchedEffect
        }
        // 系统切换路径：立即截旧界面；失败则无动画直接切
        ThemeTransition.capture(host)
        appliedDark = darkTheme
    }

    val colors = if (appliedDark) DarkColors else LightColors
    val materialScheme = if (appliedDark) colors.toMaterialDark() else colors.toMaterialLight()
    val shape = WearAdbShape(cornerRadius = screenCornerRadius())

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as ComponentActivity
            val window = activity.window

            // 窗口背景透明（主题级策略，非系统栏属性，保留在主题中）
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

            // 统一入口：enableEdgeToEdge + 显式透明/关 contrast + 图标明暗 + insets 监听兜底（180° 翻转）。
            // 重复调用仅覆盖 listener，不累积。
            // 取 appliedDark（已生效深浅）：图标明暗与圆孔揭露动画同步切换，不在目标变化时抢跑
            NavigationBarHelper.setupEdgeToEdge(activity, lightStatusBar = !appliedDark)
        }
    }

    // LocalConfiguration 深色位覆盖（对齐 PowerMeterTheme）：content 里直调
    // isSystemInDarkTheme() 的取色点不经本函数参数——覆盖后它们与圆孔擦除动画同步切换，
    // 不绕过闸门硬切。只改 NIGHT 位，density/locale 等字段原样复制。
    // LocalConfiguration 是 staticCompositionLocalOf：覆盖值变化 → 整个子树重组（放行
    // 瞬间的一次性成本），无需 recreate。
    val baseConfig = LocalConfiguration.current
    val appConfig = remember(baseConfig, appliedDark) {
        Configuration(baseConfig).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (appliedDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
    }

    CompositionLocalProvider(
        LocalWearAdbColors provides colors,
        LocalWearAdbShape provides shape,
        LocalConfiguration provides appConfig
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = WearAdbTypography
        ) {
            // Box 不加约束：尺寸由 content 决定（全屏 content → 覆盖层全屏；非全屏场景
            // 零布局影响）。覆盖层最后声明盖在 content 之上，动画期间拦截全部触摸、
            // 播完自动从组合移除（见 ThemeTransition）。
            Box {
                content()
                ThemeTransitionOverlay()
            }
        }
    }
}
