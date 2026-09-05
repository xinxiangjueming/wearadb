package com.wearadb

import android.content.Intent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.navigation.AppNavGraph
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleUsbIntent(intent)
        requestStoragePermission()
        setContent {
            CompositionLocalProvider(LocalStrings provides rememberStrings()) {
            WearAdbTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

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
