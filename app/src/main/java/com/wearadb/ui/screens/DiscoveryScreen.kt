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
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.data.repository.DiscoveredDevice
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding

@Composable
fun DiscoveryScreen(
    onBack: () -> Unit,
    onNavigateToPairing: (host: String, port: Int) -> Unit,
    viewModel: AppViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val devices by viewModel.discoveredDevices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    LaunchedEffect(Unit) { viewModel.startDiscovery() }
    DisposableEffect(Unit) { onDispose { viewModel.stopDiscovery() } }

    LaunchedEffect(connectionState) {
        if (connectionState == com.wearadb.data.repository.ConnectionState.CONNECTED) onBack()
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val connectDevices = devices.filter { !it.isPairing }
    val pairingDevices = devices.filter { it.isPairing }

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
                Text("发现设备", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
                Spacer(Modifier.weight(1f))
                if (isDiscovering) {
                    CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                }
                IconButton(onClick = {
                    if (isDiscovering) viewModel.stopDiscovery() else viewModel.startDiscovery()
                }) {
                    Icon(
                        if (isDiscovering) Icons.Outlined.Stop else Icons.Outlined.Refresh,
                        if (isDiscovering) "停止" else "扫描",
                        tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        item {
            WearCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = isDiscovering)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isDiscovering) "正在扫描局域网设备..." else "扫描已停止",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurface
                    )
                }
            }
        }

        if (connectDevices.isNotEmpty()) {
            item { SectionHeader("可连接设备 (${connectDevices.size})") }
            items(connectDevices) { device ->
                DiscoveredDeviceCard(device = device, label = "连接") {
                    viewModel.connectFromDiscovered(device)
                }
            }
        }

        if (pairingDevices.isNotEmpty()) {
            item { SectionHeader("需要配对的设备 (${pairingDevices.size})") }
            items(pairingDevices) { device ->
                DiscoveredDeviceCard(device = device, label = "配对") {
                    onNavigateToPairing(device.host, device.port)
                }
            }
        }

        if (devices.isEmpty() && !isDiscovering) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.WifiFind, null, tint = c.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("未发现设备", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        Text("确保设备已开启无线调试", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    label: String,
    onClick: () -> Unit
) {
    val c = WearAdbTheme.colors
    WearCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (device.isPairing) Icons.Outlined.VpnKey else Icons.Outlined.PhoneAndroid,
                null, tint = if (device.isPairing) c.warning else c.accent, modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("${device.host}:${device.port}", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
            val shape = remember { RoundedCornerShape(20.dp) }
            Box(
                modifier = Modifier.clip(shape).background(c.buttonPrimary, shape)
                    .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = c.buttonPrimaryText) }
        }
    }
}
