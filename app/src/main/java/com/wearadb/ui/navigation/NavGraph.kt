// 文件: com/wearadb/ui/navigation/NavGraph.kt

package com.wearadb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.wearadb.ui.screens.*

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

    // 生成带参数的配对路由
    fun pairing(host: String = "", port: Int = 0) =
        "pairing?host=${host}&port=${port}"
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
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
        composable(Routes.SHELL) {
            ShellScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DEVICE_INFO) {
            DeviceInfoScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.APPS) {
            AppsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.FILES) {
            FilesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DISCOVERY) {
            DiscoveryScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPairing = { host, port ->
                    navController.navigate(Routes.pairing(host, port))
                }
            )
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
            PairingScreen(
                onBack = { navController.popBackStack() },
                onPaired = { navController.popBackStack() },
                initialHost = host,
                initialPort = port
            )
        }
        composable(Routes.ADVANCED) {
            AdvancedOpsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToFiles = { navController.navigate(Routes.FILES) },
                onNavigateToHome = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                }
            )
        }
        composable(Routes.FASTBOOT) {
            FastbootScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.USB_ADB) {
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
