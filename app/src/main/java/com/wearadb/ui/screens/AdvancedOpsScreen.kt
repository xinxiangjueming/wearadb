package com.wearadb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding

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

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hPadding = adaptiveHorizontalPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = hPadding),
        contentPadding = PaddingValues(top = statusBarPad + 8.dp, bottom = navBarPad + 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.onBackground) }
                Spacer(Modifier.width(8.dp))
                Text("高级操作", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            }
        }

        // ── Display ──
        item { SectionHeader("显示") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpsButton(Icons.Outlined.Camera, "截屏", Modifier.weight(1f)) {
                    viewModel.takeScreenshot(); showScreenshot = true
                }
                OpsButton(Icons.Outlined.LightMode, "亮屏", Modifier.weight(1f)) { viewModel.screenOn() }
                OpsButton(Icons.Outlined.DarkMode, "息屏", Modifier.weight(1f)) { viewModel.screenOff() }
            }
        }

        // ── Volume ──
        item { SectionHeader("音量") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpsButton(Icons.AutoMirrored.Outlined.VolumeUp, "增大", Modifier.weight(1f)) { viewModel.volumeUp() }
                OpsButton(Icons.AutoMirrored.Outlined.VolumeDown, "减小", Modifier.weight(1f)) { viewModel.volumeDown() }
                OpsButton(Icons.AutoMirrored.Outlined.VolumeOff, "静音", Modifier.weight(1f)) { viewModel.volumeMute() }
            }
        }

        // ── Connectivity ──
        item { SectionHeader("连接") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpsButton(Icons.Outlined.Wifi, "开WiFi", Modifier.weight(1f)) { viewModel.enableWifi() }
                OpsButton(Icons.Outlined.WifiOff, "关WiFi", Modifier.weight(1f)) { viewModel.disableWifi() }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpsButton(Icons.Outlined.Bluetooth, "开蓝牙", Modifier.weight(1f)) { viewModel.enableBluetooth() }
                OpsButton(Icons.Outlined.BluetoothDisabled, "关蓝牙", Modifier.weight(1f)) { viewModel.disableBluetooth() }
            }
        }

        // ── Navigation ──
        item { SectionHeader("导航") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpsButton(Icons.Outlined.Home, "Home", Modifier.weight(1f)) {
                    viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.HOME)
                }
                OpsButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.weight(1f)) {
                    viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.BACK)
                }
                OpsButton(Icons.Outlined.PowerSettingsNew, "电源", Modifier.weight(1f)) {
                    viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.POWER)
                }
            }
        }

        // ── Media ──
        item { SectionHeader("媒体") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpsButton(Icons.Outlined.SkipPrevious, "上一曲", Modifier.weight(1f)) {
                    viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_PREVIOUS)
                }
                OpsButton(Icons.Outlined.PlayArrow, "播放/暂停", Modifier.weight(1f)) {
                    viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_PLAY_PAUSE)
                }
                OpsButton(Icons.Outlined.SkipNext, "下一曲", Modifier.weight(1f)) {
                    viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_NEXT)
                }
            }
        }

        // ── Reboot ──
        item { SectionHeader("电源") }
        item {
            OpsButton(Icons.Outlined.RestartAlt, "重启设备", Modifier.fillMaxWidth()) { showRebootDialog = true }
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
            confirmButton = { TextButton(onClick = { showScreenshot = false; viewModel.clearScreenshot() }) { Text("关闭", color = c.accent) } }
        )
    }
}

@Composable
private fun OpsButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = WearAdbTheme.colors
    val cr = WearAdbTheme.shape.cornerRadius
    val shape = remember { RoundedCornerShape(cr) }
    Column(
        modifier = modifier
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
