package com.wearadb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.adb.UsbAdbConnectionState
import com.wearadb.adb.UsbAdbDeviceInfo
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.UsbAdbViewModel
import com.wearadb.ui.theme.WearAdbTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbAdbScreen(
    onBack: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit = {},
    onNavigateToShell: () -> Unit = {},
    onNavigateToApps: () -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
    onNavigateToAdvanced: () -> Unit = {},
    viewModel: UsbAdbViewModel = hiltViewModel()
) {
    val colors = WearAdbTheme.colors
    val shape = WearAdbTheme.shape
    val cornerRadius = shape.cornerRadius
    val s = LocalStrings.current

    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val usbAdbDevices by viewModel.devices.collectAsState()
    val connectLog by viewModel.connectLog.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.scanDevices()
    }

    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.featureUsbAdb) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.btnBack)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.scanDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = s.btnRefresh)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                    navigationIconContentColor = colors.onBackground,
                    actionIconContentColor = colors.iconTint
                )
            )
        },
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = navBarPad),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 连接状态
            WiredAdbConnectionStatusCard(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                cornerRadius = cornerRadius,
                onDisconnect = { viewModel.disconnect() },
                s = s
            )

            // 设备列表（未连接时）
            if (connectionState != UsbAdbConnectionState.CONNECTED) {
                WiredAdbDeviceListCard(
                    devices = usbAdbDevices,
                    cornerRadius = cornerRadius,
                    onConnect = { viewModel.connect(it) },
                    onRefresh = { viewModel.scanDevices() },
                    s = s
                )
            }

            // 连接日志（未连接时）
            if (connectLog.isNotEmpty() && connectionState != UsbAdbConnectionState.CONNECTED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(cornerRadius),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(s.usbConnectLog, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(connectLog, color = colors.onSurfaceDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                    }
                }
            }

            // ── 已连接：功能导航卡片 ──
            if (connectionState == UsbAdbConnectionState.CONNECTED) {
                Text(s.sectionTools, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 4.dp))

                // 设备信息
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.Info,
                    title = s.deviceInfoTitle,
                    subtitle = s.usbDescInfo,
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToDeviceInfo
                )

                // Shell 命令
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.Terminal,
                    title = s.featureShell,
                    subtitle = s.usbDescShell,
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToShell
                )

                // 应用管理
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.Apps,
                    title = s.appsTitle,
                    subtitle = s.usbDescApps,
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToApps
                )

                // 文件管理
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.FolderOpen,
                    title = s.filesTitle,
                    subtitle = s.usbDescFiles,
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToFiles
                )

                // 高级功能
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.DeveloperBoard,
                    title = s.featureAdvanced,
                    subtitle = s.usbDescAdvanced,
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToAdvanced
                )

                // 重启到 Fastboot
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.RestartAlt,
                    title = s.usbRebootFastboot,
                    subtitle = s.usbRebootFastbootDesc,
                    cornerRadius = cornerRadius,
                    onClick = { viewModel.rebootToBootloader() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UsbAdbFeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val colors = WearAdbTheme.colors
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(subtitle, color = colors.onSurfaceDim, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = colors.iconTint)
        }
    }
}

@Composable
private fun WiredAdbConnectionStatusCard(
    connectionState: UsbAdbConnectionState,
    connectedDevice: UsbAdbDeviceInfo?,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onDisconnect: () -> Unit,
    s: com.wearadb.ui.Strings
) {
    val colors = WearAdbTheme.colors
    val statusColor = when (connectionState) {
        UsbAdbConnectionState.CONNECTED -> colors.statusDotActive
        UsbAdbConnectionState.CONNECTING -> colors.warning
        UsbAdbConnectionState.ERROR -> colors.error
        UsbAdbConnectionState.DISCONNECTED -> colors.statusDotInactive
    }
    val statusText = when (connectionState) {
        UsbAdbConnectionState.CONNECTED -> s.usbConnectedFmt(connectedDevice?.displayName ?: "")
        UsbAdbConnectionState.CONNECTING -> s.statusConnecting
        UsbAdbConnectionState.ERROR -> s.statusError
        UsbAdbConnectionState.DISCONNECTED -> s.usbNotConnected
    }

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(statusColor))
            Spacer(modifier = Modifier.width(12.dp))
            Text(statusText, color = colors.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (connectionState == UsbAdbConnectionState.CONNECTED) {
                TextButton(onClick = onDisconnect) {
                    Text(s.btnDisconnect, color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun WiredAdbDeviceListCard(
    devices: List<UsbAdbDeviceInfo>,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onConnect: (UsbAdbDeviceInfo) -> Unit,
    onRefresh: () -> Unit,
    s: com.wearadb.ui.Strings
) {
    val colors = WearAdbTheme.colors

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.usbDetectedDevices, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, s.btnRefresh, tint = colors.iconTint, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    s.usbNoDeviceHint,
                    color = colors.onSurfaceDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(cornerRadius / 2))
                            .border(1.dp, colors.border, RoundedCornerShape(cornerRadius / 2))
                            .clickable { onConnect(device) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Usb, null, tint = colors.accent, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.displayName, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(s.usbSerial(device.serialNumber), color = colors.onSurfaceDim, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, s.btnConnect, tint = colors.iconTint)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
