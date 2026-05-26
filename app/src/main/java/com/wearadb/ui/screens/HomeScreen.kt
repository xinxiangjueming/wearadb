package com.wearadb.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.data.repository.ConnectionState
import com.wearadb.data.model.SavedDevice
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.utils.*

@Composable
fun HomeScreen(
    onNavigateToShell: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateToAdvanced: () -> Unit,
    viewModel: AppViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val connectionState by viewModel.connectionState.collectAsState()
    val deviceBanner by viewModel.deviceBanner.collectAsState()
    val devices by viewModel.devices.collectAsState(initial = emptyList())
    val lastHost by viewModel.lastHost.collectAsState()
    val lastPort by viewModel.lastPort.collectAsState()

    val expanded = isExpandedScreen()
    val hPadding = adaptiveHorizontalPadding()

    val statusBarPad = remember {
        mutableStateOf(0.dp)
    }
    val navBarPad = remember {
        mutableStateOf(0.dp)
    }
    val insets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    statusBarPad.value = insets.asPaddingValues().calculateTopPadding()
    navBarPad.value = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var hostInput by remember(lastHost) { mutableStateOf(lastHost) }
    var portInput by remember(lastPort) { mutableStateOf(lastPort.toString()) }
    val isConnecting by remember(connectionState) {
        derivedStateOf { connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.AUTHENTICATING }
    }
    val isConnected by remember(connectionState) {
        derivedStateOf { connectionState == ConnectionState.CONNECTED }
    }

    if (expanded) {
        // ── 大屏：左右分栏 ──
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .padding(horizontal = hPadding)
                .padding(top = statusBarPad.value + 16.dp, bottom = navBarPad.value + 16.dp)
        ) {
            // 左栏：连接控制
            LazyColumn(
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("wear-adb", style = MaterialTheme.typography.displayLarge, color = c.onBackground)
                        Text("无线调试工具", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    }
                }
                item {
                    WearCard {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            StatusDot(active = isConnected)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.DISCONNECTED -> "未连接"
                                    ConnectionState.CONNECTING -> "连接中..."
                                    ConnectionState.AUTHENTICATING -> "等待授权..."
                                    ConnectionState.CONNECTED -> "已连接"
                                    ConnectionState.ERROR -> "连接失败"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = c.onSurface
                            )
                        }
                        if (isConnected && deviceBanner.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(deviceBanner, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        WearInput(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = "IP",
                            placeholder = "192.168.1.100",
                            modifier = Modifier.fillMaxWidth(),
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next
                        )
                        Spacer(Modifier.height(10.dp))
                        WearInput(
                            value = portInput,
                            onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                            label = "端口",
                            placeholder = "5555",
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        if (isConnected) {
                            WearButton(text = "断开", onClick = { viewModel.disconnect() }, variant = ButtonVariant.Danger)
                        } else {
                            WearButton(
                                text = if (isConnecting) "连接中..." else "连接",
                                onClick = { viewModel.connect(hostInput.trim(), portInput.toIntOrNull() ?: 5555) },
                                enabled = hostInput.isNotBlank() && !isConnecting
                            )
                            Spacer(Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                WearButton(
                                    text = "发现设备",
                                    onClick = onNavigateToDiscovery,
                                    variant = ButtonVariant.Secondary
                                )
                                WearButton(
                                    text = "配对",
                                    onClick = onNavigateToPairing,
                                    variant = ButtonVariant.Secondary
                                )
                            }
                        }
                        AnimatedVisibility(visible = connectionState == ConnectionState.ERROR) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Text("无法连接到设备，请检查 IP 和端口", style = MaterialTheme.typography.bodySmall, color = c.error)
                            }
                        }
                    }
                }
                // 历史设备
                if (devices.isNotEmpty()) {
                    item { SectionHeader("历史设备") }
                    items(items = devices, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            onConnect = {
                                hostInput = device.host
                                portInput = device.port.toString()
                                viewModel.connect(device.host, device.port)
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(device.address) },
                            onRemove = { viewModel.removeDevice(device.address) }
                        )
                    }
                }
            }

            // 右栏：工具区（仅连接后显示）
            if (isConnected) {
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { SectionHeader("工具") }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard(Icons.Outlined.Terminal, "Shell", "交互终端", Modifier.weight(1f), onNavigateToShell)
                            FeatureCard(Icons.Outlined.PhoneAndroid, "设备", "信息概览", Modifier.weight(1f), onNavigateToDeviceInfo)
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard(Icons.Outlined.Apps, "应用", "管理应用", Modifier.weight(1f), onNavigateToApps)
                            FeatureCard(Icons.Outlined.Folder, "文件", "浏览文件", Modifier.weight(1f), onNavigateToFiles)
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard(Icons.Outlined.Build, "高级", "重启/截屏/音量", Modifier.weight(1f), onNavigateToAdvanced)
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    } else {
        // ── 小屏：单栏布局（原有逻辑） ──
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .padding(horizontal = hPadding),
            contentPadding = PaddingValues(
                top = statusBarPad.value + 16.dp,
                bottom = navBarPad.value + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("wear-adb", style = MaterialTheme.typography.displayLarge, color = c.onBackground)
                    Text("无线调试工具", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                }
            }
            item {
                WearCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        StatusDot(active = isConnected)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = when (connectionState) {
                                ConnectionState.DISCONNECTED -> "未连接"
                                ConnectionState.CONNECTING -> "连接中..."
                                ConnectionState.AUTHENTICATING -> "等待授权..."
                                ConnectionState.CONNECTED -> "已连接"
                                ConnectionState.ERROR -> "连接失败"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = c.onSurface
                        )
                    }
                    if (isConnected && deviceBanner.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(deviceBanner, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    WearInput(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = "IP",
                        placeholder = "192.168.1.100",
                        modifier = Modifier.fillMaxWidth(),
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next
                    )
                    Spacer(Modifier.height(10.dp))
                    WearInput(
                        value = portInput,
                        onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                        label = "端口",
                        placeholder = "5555",
                        modifier = Modifier.fillMaxWidth(0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    if (isConnected) {
                        WearButton(text = "断开", onClick = { viewModel.disconnect() }, variant = ButtonVariant.Danger)
                    } else {
                        WearButton(
                            text = if (isConnecting) "连接中..." else "连接",
                            onClick = { viewModel.connect(hostInput.trim(), portInput.toIntOrNull() ?: 5555) },
                            enabled = hostInput.isNotBlank() && !isConnecting
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WearButton(
                                text = "发现设备",
                                onClick = onNavigateToDiscovery,
                                modifier = Modifier.weight(1f),
                                variant = ButtonVariant.Secondary
                            )
                            WearButton(
                                text = "配对",
                                onClick = onNavigateToPairing,
                                modifier = Modifier.weight(1f),
                                variant = ButtonVariant.Secondary
                            )
                        }
                    }
                    AnimatedVisibility(visible = connectionState == ConnectionState.ERROR) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Text("无法连接到设备，请检查 IP 和端口", style = MaterialTheme.typography.bodySmall, color = c.error)
                        }
                    }
                }
            }
            if (isConnected) {
                item { SectionHeader("工具") }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeatureCard(Icons.Outlined.Terminal, "Shell", "交互终端", Modifier.weight(1f), onNavigateToShell)
                        FeatureCard(Icons.Outlined.PhoneAndroid, "设备", "信息概览", Modifier.weight(1f), onNavigateToDeviceInfo)
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeatureCard(Icons.Outlined.Apps, "应用", "管理应用", Modifier.weight(1f), onNavigateToApps)
                        FeatureCard(Icons.Outlined.Folder, "文件", "浏览文件", Modifier.weight(1f), onNavigateToFiles)
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeatureCard(Icons.Outlined.Build, "高级", "重启/截屏/音量", Modifier.weight(1f), onNavigateToAdvanced)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (devices.isNotEmpty()) {
                item { SectionHeader("历史设备") }
                items(items = devices, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = {
                            hostInput = device.host
                            portInput = device.port.toString()
                            viewModel.connect(device.host, device.port)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(device.address) },
                        onRemove = { viewModel.removeDevice(device.address) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = WearAdbTheme.colors
    val shape = remember { RoundedCornerShape(28.dp) }
    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .background(c.surfaceVariant, shape)
            .border(1.dp, c.outlineVariant, shape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, title, tint = c.accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

@Composable
private fun DeviceCard(
    device: SavedDevice,
    onConnect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit
) {
    val c = WearAdbTheme.colors
    WearCard(onClick = onConnect) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                if (device.name.isNotEmpty()) {
                    Text(device.address, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (device.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    "收藏",
                    tint = if (device.isFavorite) c.accent else c.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, "删除", tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
