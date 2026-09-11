// 文件: com/wearadb/ui/navigation/NavGraph.kt

@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.wearadb.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.wearadb.ui.screens.*

// Material 标准容器过渡时长（medium motion = 300ms）
private const val TRANSITION_DURATION = 300

/**
 * 把当前 composable 的 [AnimatedContentScope]（即 animatedVisibilityScope）透传给页面内部，
 * 供共享元素（sharedElement）使用。每个 destination 的 content lambda 中 `this` 即为此作用域。
 */
val LocalNavTransitionScope = compositionLocalOf<AnimatedContentScope?> { null }

/**
 * 把 [SharedTransitionLayout] 的 [SharedTransitionScope] 透传给页面内部。
 * 共享元素（sharedElement / rememberSharedContentState）是 SharedTransitionScope 的成员扩展，
 * 必须在该作用域内才能调用。
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * 共享元素标题：在导航作用域内时，标题会与来源页（Home 功能卡片）的同 route 共享元素
 * 平滑 morph；不在导航作用域内（如预览）时退化为普通 Text。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTitle(
    route: String,
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val stScope = LocalSharedTransitionScope.current
    val avScope = LocalNavTransitionScope.current
    val resolved = if (stScope != null && avScope != null) {
        with(stScope) {
            modifier.sharedElement(rememberSharedContentState(route), avScope)
        }
    } else modifier
    Text(text = text, style = style, color = color, modifier = resolved)
}

object Routes {
    const val HOME = "home"
    const val SHELL = "shell"
    const val DEVICE_INFO = "device_info"
    const val APPS = "apps"
    const val FILES = "files"
    const val DISCOVERY = "discovery"
    const val PAIRING = "pairing"
    const val ADVANCED = "advanced"
    const val FASTBOOT = "fastboot"
    const val USB_ADB = "usb_adb"
    const val SCREEN_MIRROR = "screen_mirror"

    // 生成带参数的配对路由
    fun pairing(host: String = "", port: Int = 0) =
        "pairing?host=${host}&port=${port}"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavGraph(navController: NavHostController) {
    SharedTransitionLayout {
        // 把 SharedTransitionScope 注入 Local，供各 destination 内部调用 sharedElement
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                // ── 统一 Material 横向滑动 + 淡入淡出（应用于全部 12 个 destination）──
                // 前进：新页从右滑入(Start)、旧页向左滑出；返回：旧页向右滑出(End)、上一页从左滑回
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(TRANSITION_DURATION)
                    ) + fadeIn(animationSpec = tween(TRANSITION_DURATION))
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = tween(TRANSITION_DURATION)
                    ) + fadeOut(animationSpec = tween(TRANSITION_DURATION))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = tween(TRANSITION_DURATION)
                    ) + fadeIn(animationSpec = tween(TRANSITION_DURATION))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(TRANSITION_DURATION)
                    ) + fadeOut(animationSpec = tween(TRANSITION_DURATION))
                }
            ) {
                composable(Routes.HOME) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        HomeScreen(
                            onNavigateToShell = { navController.navigate(Routes.SHELL) },
                            onNavigateToDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                            onNavigateToApps = { navController.navigate(Routes.APPS) },
                            onNavigateToFiles = { navController.navigate(Routes.FILES) },
                            onNavigateToDiscovery = { navController.navigate(Routes.DISCOVERY) },
                            onNavigateToPairing = { navController.navigate(Routes.pairing()) },  // 手动配对无参数
                            onNavigateToAdvanced = { navController.navigate(Routes.ADVANCED) },
                            onNavigateToFastboot = { navController.navigate(Routes.FASTBOOT) },
                            onNavigateToUsbAdb = { navController.navigate(Routes.USB_ADB) }
                        )
                    }
                }
                composable(Routes.SHELL) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        ShellScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.DEVICE_INFO) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        DeviceInfoScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.APPS) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        AppsScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.FILES) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        FilesScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.DISCOVERY) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        DiscoveryScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToPairing = { host, port ->
                                navController.navigate(Routes.pairing(host, port))
                            }
                        )
                    }
                }
                composable(
                    route = "pairing?host={host}&port={port}",
                    arguments = listOf(
                        navArgument("host") { defaultValue = "" },
                        navArgument("port") { defaultValue = 0 }
                    )
                ) { backStackEntry ->
                    val host = backStackEntry.arguments?.getString("host") ?: ""
                    val port = backStackEntry.arguments?.getInt("port") ?: 0
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        PairingScreen(
                            onBack = { navController.popBackStack() },
                            onPaired = { navController.popBackStack() },
                            initialHost = host,
                            initialPort = port
                        )
                    }
                }
                composable(Routes.ADVANCED) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        AdvancedOpsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToFiles = { navController.navigate(Routes.FILES) },
                            onNavigateToHome = {
                                navController.popBackStack(Routes.HOME, inclusive = false)
                            },
                            onNavigateToScreenMirror = { navController.navigate(Routes.SCREEN_MIRROR) }
                        )
                    }
                }
                composable(Routes.SCREEN_MIRROR) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        ScreenMirrorScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.FASTBOOT) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        FastbootScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(Routes.USB_ADB) {
                    CompositionLocalProvider(LocalNavTransitionScope provides this) {
                        UsbAdbScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                            onNavigateToShell = { navController.navigate(Routes.SHELL) },
                            onNavigateToApps = { navController.navigate(Routes.APPS) },
                            onNavigateToFiles = { navController.navigate(Routes.FILES) },
                            onNavigateToAdvanced = { navController.navigate(Routes.ADVANCED) }
                        )
                    }
                }
            }
        }
    }
}
