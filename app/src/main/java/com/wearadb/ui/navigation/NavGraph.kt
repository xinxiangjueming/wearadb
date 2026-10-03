// 文件: com/wearadb/ui/navigation/NavGraph.kt

@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.wearadb.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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

/**
 * 把当前 composable 的 [AnimatedContentScope]（即 animatedVisibilityScope）透传给页面内部，
 * 供一镜到底转场（[RevealPage] 读它的 transition 进度）与共享元素使用。
 * 每个 destination 的 content lambda 中 `this` 即为此作用域。
 */
val LocalNavTransitionScope = compositionLocalOf<AnimatedContentScope?> { null }

/**
 * 共享元素作用域（旧滑动转场时代遗留）。当前 AppNavGraph 已改为一镜到底转场
 * （不再 provide 本作用域），恒为 null —— [SharedTitle]/FeatureCard/WearButton 的
 * sharedElement 路径自动退化为普通 Text，保留定义仅为兼容既有调用点。
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * 页面标题（旧共享元素 morph 的兼容壳）。不在导航作用域内（如预览）时退化为普通 Text。
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
    // 一镜到底转场下不再做标题 morph：标题随内容层整体延迟淡入
    Text(text = text, style = style, color = color, modifier = modifier)
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

/** FastOutLinearIn 曲线（compose core 未提供，收拢侧用） */
private val FastOutLinearInEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/**
 * 一镜到底导航图（2026-10-03 起整体替换旧的横向滑动 + 淡入淡出 + 标题 morph）。
 *
 * 机制：NavHost 的转场动画本体是 **0 位移 slide**（无视觉变化，仅提供动画窗口，
 * 保证旧页在转场期间保持组合与静止可见——AnimatedContent 双页同显），全部观感由
 * [RevealPage] 的裁剪几何 + 透明度纯函数驱动（详见 RevealTransition.kt 头注释）。
 * 无锚点/截图失败的跳转自动降级为直接显示，无视觉异常。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavGraph(navController: NavHostController) {
    // 统一出口：点击瞬间截图登记一镜到底会话，再导航（截图/锚点缺失自动降级直切）。
    // 锚点 key 必须取 **base route**：带参路由（pairing?host=..&port=..）的锚点登记在
    // base 上（FeatureCard/WearButton 的 sharedKey = Routes.XXX），用完整路由去查会
    // 取不到槽位 → 无会话 → 直切（首页配对按钮曾因此没有动画）。
    val reveal = rememberRevealLauncher()
    fun nav(route: String) = reveal(route.substringBefore('?')) { navController.navigate(route) }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        // ── 0 位移 slide：只提供转场窗口（时长/曲线与 RevealPage 的几何动画同参），
        //    几何 + 淡变全部由 RevealPage 驱动；旧页静止保持可见直至转场结束 ──
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { 0 },
                animationSpec = tween(REVEAL_OPEN_MS, easing = FastOutSlowInEasing)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { 0 },
                animationSpec = tween(REVEAL_OPEN_MS, easing = FastOutSlowInEasing)
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { 0 },
                animationSpec = tween(REVEAL_CLOSE_MS, easing = FastOutLinearInEasing)
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { 0 },
                animationSpec = tween(REVEAL_CLOSE_MS, easing = FastOutLinearInEasing)
            )
        }
    ) {
        composable(Routes.HOME) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                HomeScreen(
                    onNavigateToShell = { nav(Routes.SHELL) },
                    onNavigateToDeviceInfo = { nav(Routes.DEVICE_INFO) },
                    onNavigateToApps = { nav(Routes.APPS) },
                    onNavigateToFiles = { nav(Routes.FILES) },
                    onNavigateToDiscovery = { nav(Routes.DISCOVERY) },
                    onNavigateToPairing = { nav(Routes.pairing()) },  // 手动配对无参数
                    onNavigateToAdvanced = { nav(Routes.ADVANCED) },
                    onNavigateToFastboot = { nav(Routes.FASTBOOT) },
                    onNavigateToUsbAdb = { nav(Routes.USB_ADB) }
                )
            }
        }
        composable(Routes.SHELL) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.SHELL) {
                    ShellScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        composable(Routes.DEVICE_INFO) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.DEVICE_INFO) {
                    DeviceInfoScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        composable(Routes.APPS) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.APPS) {
                    AppsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        composable(Routes.FILES) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.FILES) {
                    FilesScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        composable(Routes.DISCOVERY) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.DISCOVERY) {
                    DiscoveryScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToPairing = { host, port ->
                            nav(Routes.pairing(host, port))
                        }
                    )
                }
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
                RevealPage(Routes.PAIRING) {
                    PairingScreen(
                        onBack = { navController.popBackStack() },
                        onPaired = { navController.popBackStack() },
                        initialHost = host,
                        initialPort = port
                    )
                }
            }
        }
        composable(Routes.ADVANCED) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.ADVANCED) {
                    AdvancedOpsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToFiles = { nav(Routes.FILES) },
                        onNavigateToHome = {
                            navController.popBackStack(Routes.HOME, inclusive = false)
                        },
                        onNavigateToScreenMirror = { nav(Routes.SCREEN_MIRROR) }
                    )
                }
            }
        }
        composable(Routes.SCREEN_MIRROR) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.SCREEN_MIRROR) {
                    ScreenMirrorScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        composable(Routes.FASTBOOT) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.FASTBOOT) {
                    FastbootScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
        composable(Routes.USB_ADB) {
            CompositionLocalProvider(LocalNavTransitionScope provides this) {
                RevealPage(Routes.USB_ADB) {
                    UsbAdbScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToDeviceInfo = { nav(Routes.DEVICE_INFO) },
                        onNavigateToShell = { nav(Routes.SHELL) },
                        onNavigateToApps = { nav(Routes.APPS) },
                        onNavigateToFiles = { nav(Routes.FILES) },
                        onNavigateToAdvanced = { nav(Routes.ADVANCED) }
                    )
                }
            }
        }
    }
}
