package com.wearadb.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 大屏适配工具类
 * 断点标准（Google Material 3）：
 * - Compact: width < 600dp  （手机竖屏）
 * - Medium:  600dp ≤ width < 840dp （折叠屏内屏、平板竖屏）
 * - Expanded: width ≥ 840dp （平板横屏）
 */

/** 判断当前窗口是否为大屏（Medium 或 Expanded） */
@Composable
@ReadOnlyComposable
fun isExpandedScreen(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp >= 600
}

/** 返回适配后的水平边距：Compact 20dp，Medium 32dp，Expanded 48dp */
@Composable
@ReadOnlyComposable
fun adaptiveHorizontalPadding(): Dp {
    val config = LocalConfiguration.current
    return when {
        config.screenWidthDp >= 840 -> 48.dp
        config.screenWidthDp >= 600 -> 32.dp
        else -> 20.dp
    }
}

/** 返回内容区最大宽度：Medium 600dp，Expanded 800dp，Compact 无限制 */
@Composable
@ReadOnlyComposable
fun contentMaxWidth(): Dp {
    val config = LocalConfiguration.current
    return when {
        config.screenWidthDp >= 840 -> 800.dp
        config.screenWidthDp >= 600 -> 600.dp
        else -> Dp.Unspecified
    }
}
