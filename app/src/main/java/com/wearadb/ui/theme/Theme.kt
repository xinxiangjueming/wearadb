package com.wearadb.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
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
    warning            = WarnYellow,
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
    warning            = WarnYellow,
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

@Composable
fun WearAdbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = if (darkTheme) DarkColors else LightColors
    val materialScheme = if (darkTheme) colors.toMaterialDark() else colors.toMaterialLight()
    val shape = WearAdbShape(cornerRadius = screenCornerRadius())

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as ComponentActivity
            val window = activity.window

            // 窗口背景透明（主题级策略，非系统栏属性，保留在主题中）
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

            // 统一入口：enableEdgeToEdge + 显式透明/关 contrast + 图标明暗 + insets 监听兜底（180° 翻转）。
            // 重复调用仅覆盖 listener，不累积。
            NavigationBarHelper.setupEdgeToEdge(activity, lightStatusBar = !darkTheme)
        }
    }

    CompositionLocalProvider(
        LocalWearAdbColors provides colors,
        LocalWearAdbShape provides shape
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = WearAdbTypography,
            content = content
        )
    }
}
