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
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding

private data class OpsItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
fun AdvancedOpsScreen(
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToHome: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
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
    val s = LocalStrings.current

    val sections = remember {
        listOf(
            s.opsDisplay to listOf(
                OpsItem(Icons.Outlined.Camera, s.opsScreenshot) { showScreenshot = true },
                OpsItem(Icons.Outlined.LightMode, s.opsScreenOn) { viewModel.screenOn() },
                OpsItem(Icons.Outlined.DarkMode, s.opsScreenOff) { viewModel.screenOff() },
            ),
            s.opsVolume to listOf(
                OpsItem(Icons.AutoMirrored.Outlined.VolumeUp, s.opsVolUp) { viewModel.volumeUp() },
                OpsItem(Icons.AutoMirrored.Outlined.VolumeDown, s.opsVolDown) { viewModel.volumeDown() },
                OpsItem(Icons.AutoMirrored.Outlined.VolumeOff, s.opsVolMute) { viewModel.volumeMute() },
            ),
            s.opsConnectivity to listOf(
                OpsItem(Icons.Outlined.Wifi, s.opsWifiOn) { viewModel.enableWifi() },
                OpsItem(Icons.Outlined.WifiOff, s.opsWifiOff) { viewModel.disableWifi() },
                OpsItem(Icons.Outlined.Bluetooth, s.opsBtOn) { viewModel.enableBluetooth() },
                OpsItem(Icons.Outlined.BluetoothDisabled, s.opsBtOff) { viewModel.disableBluetooth() },
            ),
            s.opsNavigation to listOf(
                OpsItem(Icons.Outlined.Home, "Home") { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.HOME) },
                OpsItem(Icons.AutoMirrored.Outlined.ArrowBack, s.opsBack) { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.BACK) },
                OpsItem(Icons.Outlined.PowerSettingsNew, s.opsPower) { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.POWER) },
            ),
            s.opsMedia to listOf(
                OpsItem(Icons.Outlined.SkipPrevious, s.opsPrev) { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_PREVIOUS) },
                OpsItem(Icons.Outlined.PlayArrow, s.opsPlayPause) { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_PLAY_PAUSE) },
                OpsItem(Icons.Outlined.SkipNext, s.opsNext) { viewModel.keyEvent(com.wearadb.adb.AdvancedOps.KeyCodes.MEDIA_NEXT) },
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
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground) }
                Spacer(Modifier.width(8.dp))
                Text(s.advancedTitle, style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
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
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s.opsPower) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OpsButton(Icons.Outlined.RestartAlt, s.opsRebootDevice) { showRebootDialog = true }
        }
    }

    // ── Reboot Dialog ──
    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius),
            title = { Text(s.opsRebootDevice, style = MaterialTheme.typography.titleMedium, color = c.onSurface) },
            text = { Text(s.opsSelectReboot, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.reboot(); showRebootDialog = false }) {
                        Text(s.opsRebootNormal, color = c.accent)
                    }
                    TextButton(onClick = {
                        viewModel.reboot("recovery")
                        showRebootDialog = false
                        onNavigateToHome()
                    }) {
                        Text("Recovery 模式", color = c.warning)
                    }
                    TextButton(onClick = {
                        viewModel.reboot("bootloader")
                        showRebootDialog = false
                        onNavigateToHome()
                    }) {
                        Text("Bootloader 模式", color = c.warning)
                    }
                    TextButton(onClick = {
                        viewModel.reboot("shutdown")
                        showRebootDialog = false
                        onNavigateToHome()
                    }) {
                        Text(s.opsShutdown, color = c.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text(s.btnCancel, color = c.onSurfaceVariant) }
            }
        )
    }

    // ── Screenshot Dialog ──
    if (showScreenshot) {
        LaunchedEffect(Unit) { viewModel.takeScreenshot() }
        AlertDialog(
            onDismissRequest = { showScreenshot = false; viewModel.clearScreenshot() },
            containerColor = c.surface,
            shape = RoundedCornerShape(WearAdbTheme.shape.cornerRadius),
            title = { Text(s.opsScreenshot, style = MaterialTheme.typography.titleMedium, color = c.onSurface) },
            text = {
                if (screenshotLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                    }
                } else if (screenshotData != null) {
                    Text(s.screenshotSuccess.format("${screenshotData!!.size / 1024}KB"), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                } else {
                    Text(s.screenshotFailed, style = MaterialTheme.typography.bodyMedium, color = c.error)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (screenshotData != null) {
                        TextButton(onClick = {
                            pendingScreenshotData = screenshotData
                            screenshotSaveLauncher.launch("screenshot_${System.currentTimeMillis()}.png")
                        }) { Text(s.screenshotSave, color = c.accent) }
                    }
                    TextButton(onClick = { showScreenshot = false; viewModel.clearScreenshot() }) { Text(s.btnClose, color = c.onSurfaceVariant) }
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
