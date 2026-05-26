package com.wearadb.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding

private data class OpsItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
fun AdvancedOpsScreen(
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    viewModel: AppViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    var showRebootDialog by remember { mutableStateOf(false) }
    var showScreenshot by remember { mutableStateOf(false) }
    val screenshotData by viewModel.screenshotData.collectAsState()
    val screenshotLoading by viewModel.screenshotLoading.collectAsState()

    val context = LocalContext.current
    var pendingScreenshotData by remember { mutableStateOf<ByteArray?>(null) }
    val screenshotSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        uri?.let { target ->
            pendingScreenshotData?.let { data ->
                try {
                    context.contentResolver.openOutputStream(target)?.use { it.write(data) }
                } catch (_: Exception) {}
                pendingScreenshotData = null
            }
        }
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hPadding = adaptiveHorizontalPadding()

    val sections = remember {
        listOf(
            "显示" to listOf(
                OpsItem(Icons.Outlined.Camera, "截屏") { showScreenshot = true },
                OpsItem(Icons.Outlined.LightMode, "亮屏") { viewModel.screenOn() },
                OpsItem(Icons.Outlined.DarkMode, "息屏") { viewModel.screenOff() },
            ),
            "音量" to listOf(
                OpsItem(Icons.AutoMirrored.Outlined.VolumeUp, "增大") { viewModel.volumeUp() },
                OpsItem(Icons.AutoMirrored.Outlined.VolumeDown, "减小") { viewModel.volumeDown() },
                OpsItem(Icons.AutoMirrored.Outlined.VolumeOff, "静音") { viewModel.volumeMute() },
            ),
            "连接" to listOf(
                OpsItem(Icons.Outlined.Wifi, "开WiFi") { viewModel.enableWifi() },
                OpsItem(Icons.Outlined.WifiOff, "关WiFi") { viewModel.disableWifi() },
                OpsItem(Icons.Outlined.Bluetooth, "开蓝牙") { viewModel.enableBluetooth() },
                OpsItem(Icons.Outlined.BluetoothDisabled, "关蓝牙") { viewModel.disableBluetooth() },
            ),
            "导航" to listOf(
                OpsItem(Icons.Outlined.Home, "Home") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.HOME) },
                OpsItem(Icons.AutoMirrored.Outlined.ArrowBack, "返回") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.BACK) },
                OpsItem(Icons.Outlined.PowerSettingsNew, "电源") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.POWER) },
            ),
            "媒体" to listOf(
                OpsItem(Icons.Outlined.SkipPrevious, "上一曲") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_PREVIOUS) },
                OpsItem(Icons.Outlined.PlayArrow, "播放/暂停") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_PLAY_PAUSE) },
                OpsItem(Icons.Outlined.SkipNext, "下一曲") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_NEXT) },
            ),
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = hPadding),
        contentPadding = PaddingValues(top = statusBarPad + 8.dp, bottom = navBarPad + 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 顶栏 ──
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.onBackground) }
                Spacer(Modifier.width(8.dp))
                Text("高级操作", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            }
        }

        // ── 各分组 ──
        for ((title, items) in sections) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(title) }
            items(items) { item ->
                OpsButton(item.icon, item.label) { item.onClick() }
            }
        }

        // ── 电源（重启） ──
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("电源") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OpsButton(Icons.Outlined.RestartAlt, "重启设备") { showRebootDialog = true }
        }
    }

    // ── Reboot Dialog ──
    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius),
            title = { Text("重启设备", style = MaterialTheme.typography.titleMedium, color = c.onSurface) },
            text = { Text("选择重启模式", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.reboot(); showRebootDialog = false }) {
                        Text("正常重启", color = c.accent)
                    }
                    TextButton(onClick = { viewModel.reboot("recovery"); showRebootDialog = false }) {
                        Text("Recovery 模式", color = c.warning)
                    }
                    TextButton(onClick = { viewModel.reboot("bootloader"); showRebootDialog = false }) {
                        Text("Bootloader 模式", color = c.warning)
                    }
                    TextButton(onClick = { viewModel.reboot("shutdown"); showRebootDialog = false }) {
                        Text("关机", color = c.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text("取消", color = c.onSurfaceVariant) }
            }
        )
    }

    // ── Screenshot Dialog ──
    if (showScreenshot) {
        AlertDialog(
            onDismissRequest = { showScreenshot = false; viewModel.clearScreenshot() },
            containerColor = c.surface,
            shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius),
            title = { Text("截屏", style = MaterialTheme.typography.titleMedium, color = c.onSurface) },
            text = {
                if (screenshotLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                    }
                } else if (screenshotData != null) {
                    Text("截屏成功 (${screenshotData!!.size / 1024}KB)", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                } else {
                    Text("截屏失败", style = MaterialTheme.typography.bodyMedium, color = c.error)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (screenshotData != null) {
                        TextButton(onClick = {
                            pendingScreenshotData = screenshotData
                            screenshotSaveLauncher.launch("screenshot_${System.currentTimeMillis()}.png")
                        }) { Text("保存到手机", color = c.accent) }
                    }
                    TextButton(onClick = { showScreenshot = false; viewModel.clearScreenshot() }) { Text("关闭", color = c.onSurfaceVariant) }
                }
            }
        )
    }
}

@Composable
private fun OpsButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val c = WearAdbTheme.colors
    val cr = WearAdbTheme.shape.cornerRadius
    val shape = remember { RoundedCornerShape(cr) }
    Column(
        modifier = Modifier
            .clip(shape)
            .background(c.surfaceVariant, shape)
            .border(1.dp, c.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = c.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.onSurface)
    }
}
