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
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.theme.WearAdbTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbAdbScreen(
    onBack: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit = {},
    onNavigateToShell: () -> Unit = {},
    onNavigateToApps: () -> Unit = {},
    onNavigateToAdvanced: () -> Unit = {},
    viewModel: AppViewModel = hiltViewModel()
) {
    val colors = WearAdbTheme.colors
    val shape = WearAdbTheme.shape
    val cornerRadius = shape.cornerRadius

    val connectionState by viewModel.usbAdbConnectionState.collectAsState()
    val connectedDevice by viewModel.usbAdbConnectedDevice.collectAsState()
    val usbAdbDevices by viewModel.usbAdbDevices.collectAsState()
    val connectLog by viewModel.usbAdbConnectLog.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.scanUsbAdbDevices()
    }

    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("有线 ADB") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnectUsbAdb()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.scanUsbAdbDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "扫描")
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
        containerColor = colors.background
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
                onDisconnect = { viewModel.disconnectUsbAdb() }
            )

            // 设备列表（未连接时）
            if (connectionState != UsbAdbConnectionState.CONNECTED) {
                WiredAdbDeviceListCard(
                    devices = usbAdbDevices,
                    cornerRadius = cornerRadius,
                    onConnect = { viewModel.connectUsbAdb(it) },
                    onRefresh = { viewModel.scanUsbAdbDevices() }
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
                        Text("连接日志", color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(connectLog, color = colors.onSurfaceDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                    }
                }
            }

            // ── 已连接：功能导航卡片 ──
            if (connectionState == UsbAdbConnectionState.CONNECTED) {
                Text("功能", color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 4.dp))

                // 设备信息
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.Info,
                    title = "设备信息",
                    subtitle = "查看型号、系统版本、存储等",
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToDeviceInfo
                )

                // Shell 命令
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.Terminal,
                    title = "Shell 命令",
                    subtitle = "执行 shell 命令",
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToShell
                )

                // 应用管理
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.Apps,
                    title = "应用管理",
                    subtitle = "查看、安装、卸载应用",
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToApps
                )

                // 高级功能
                UsbAdbFeatureCard(
                    icon = Icons.Outlined.DeveloperBoard,
                    title = "高级功能",
                    subtitle = "重启、截屏、音量、WiFi、蓝牙等",
                    cornerRadius = cornerRadius,
                    onClick = onNavigateToAdvanced
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
    onDisconnect: () -> Unit
) {
    val colors = WearAdbTheme.colors
    val statusColor = when (connectionState) {
        UsbAdbConnectionState.CONNECTED -> colors.statusDotActive
        UsbAdbConnectionState.CONNECTING -> colors.warning
        UsbAdbConnectionState.ERROR -> colors.error
        UsbAdbConnectionState.DISCONNECTED -> colors.statusDotInactive
    }
    val statusText = when (connectionState) {
        UsbAdbConnectionState.CONNECTED -> "已连接: ${connectedDevice?.displayName ?: ""}"
        UsbAdbConnectionState.CONNECTING -> "连接中..."
        UsbAdbConnectionState.ERROR -> "连接失败"
        UsbAdbConnectionState.DISCONNECTED -> "未连接 — 用USB线连接Android设备"
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
                    Text("断开", color = colors.error)
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
    onRefresh: () -> Unit
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
                Text("检测到的设备", color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "刷新", tint = colors.iconTint, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    "未检测到有线ADB设备。\n请确认设备已通过USB线连接到手机，且设备已开启USB调试。",
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
                            Text("序列号: ${device.serialNumber}", color = colors.onSurfaceDim, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, "连接", tint = colors.iconTint)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
