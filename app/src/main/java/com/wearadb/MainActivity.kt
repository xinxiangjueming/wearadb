package com.wearadb

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.navigation.AppNavGraph
import com.wearadb.ui.navigation.RevealOverlay
import com.wearadb.ui.rememberStrings
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.NavigationBarHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // 由 USB_DEVICE_ATTACHED intent 自动授权的设备，供 FastbootScreen 使用
    var usbAutoDevice: UsbDevice? = null
        private set

    /**
     * 应用当前深浅（系统深浅色切换圆孔揭露动画的驱动源）：manifest configChanges 含 uiMode 后
     * 系统切换不再重建 Activity，深浅不走 isSystemInDarkTheme() 直读（后台切换时
     * ViewRootImpl 不分发配置的已知坑），由 onCreate 初值 / onConfigurationChanged /
     * onResume 兜底三处驱动；变化经 WearAdbTheme 闸门播圆孔揭露动画（ThemeTransition）。
     */
    private val darkThemeState by lazy { mutableStateOf(isNightMode()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleUsbIntent(intent)
        requestStoragePermission()
        requestNotificationPermission()
        setContent {
            CompositionLocalProvider(LocalStrings provides rememberStrings()) {
            WearAdbTheme(darkTheme = darkThemeState.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val navController = rememberNavController()
                    // 横屏时避开摄像头（挖孔/刘海）区域：displayCutout 的左右 inset 让
                    // 全部页面内容内缩（竖屏不受影响——only(Horizontal) 排除上下，
                    // 竖屏挖孔在顶部，由各页自身的 statusBars∪cutout 上内边距处理）。
                    // 露出的边条由外层 Box 的应用底色填充；RevealOverlay 不加此内缩
                    // （它用窗口坐标定位锚点截图，必须铺满整窗）。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                            )
                    ) {
                        AppNavGraph(navController = navController)
                    }
                    // 一镜到底锚点截图层：钉在源卡片位置交叉淡变（画在 NavHost 之上）
                    RevealOverlay()
                }
            }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 深浅色切换（configChanges 含 uiMode → 不重建）在这里驱动 darkThemeState，
        // 经 WearAdbTheme 闸门播圆孔揭露动画（ThemeTransition）
        val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (darkThemeState.value != dark) darkThemeState.value = dark
        // manifest 已声明 configChanges（旋转不重建），此处重放 edge-to-edge，
        // 否则旋转后窗口退回非沉浸、导航栏恢复不透明默认色
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
        // 系统可能在配置变更后按主题重放窗口属性，延迟一帧再设一次兜底
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
        }
        // 不调 recreate()：Compose 通过 LocalConfiguration/WindowInsets 自动响应配置变化
    }

    override fun onResume() {
        super.onResume()
        // 后台切换深浅兜底（configChanges 含 uiMode 的已知坑：后台时 ViewRootImpl 不分发
        // 配置，Compose 不跟随）——回前台读 Resources 最新值，变化经 WearAdbTheme 闸门播
        // 圆孔揭露（回前台瞬间窗口表面停留的旧主题帧正是揭露的起点画面）。
        // 同时重放 edge-to-edge：错过的变化可能没有配置回调送达，不重放的话揭露播完后
        // 系统栏图标色仍停在旧主题（对齐 PowerMeter MainActivity.onResume 的做法）
        val night = isNightMode()
        if (darkThemeState.value != night) {
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !night)
            darkThemeState.value = night
        }
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不影响应用内反馈，通知仅在下次授予后显示 */ }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null) {
                usbAutoDevice = device
                Log.d(TAG, "USB_DEVICE_ATTACHED: ${device.deviceName}, " +
                    "vendor=${device.vendorId}, product=${device.productId} — permission auto-granted")
            }
        }
    }
}
