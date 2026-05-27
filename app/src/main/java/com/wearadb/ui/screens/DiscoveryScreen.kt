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
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding
import com.wearadb.ui.utils.useDualPane

@Composable
fun DiscoveryScreen(
    onBack: () -> Unit,
    onNavigateToPairing: (host: String, port: Int) -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val devices by viewModel.discoveredDevices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lastHost by viewModel.lastHost.collectAsState()
    val lastPort by viewModel.lastPort.collectAsState()

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
    val expanded = useDualPane()
    val s = LocalStrings.current

    // ── Top bar ──
    val topBar: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground) }
            Spacer(Modifier.width(8.dp))
            Text(s.discoveryTitle, style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
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
                    if (isDiscovering) s.btnStop else s.btnScan,
                    tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (expanded) {
        // ── 横屏：左栏手动连接 + 右栏所有发现设备 ──
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPadding)
                .padding(top = statusBarPad + 8.dp)
        ) {
            // 左栏：手动连接
            LazyColumn(
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = navBarPad + 32.dp)
            ) {
                item { topBar() }
                item {
                    WearCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(active = isDiscovering)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (isDiscovering) s.discoveryScanning else s.discoveryStopped,
                                style = MaterialTheme.typography.bodyMedium, color = c.onSurface
                            )
                        }
                    }
                }
                item { SectionHeader(s.manualConnectTitle) }
                item { ManualConnectCard(lastHost, lastPort, onConnect = { host, port -> viewModel.connect(host, port) }) }
            }

            // 右栏：所有发现的设备
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
            LazyColumn(
                modifier = Modifier.weight(1f).padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = navBarPad + 32.dp)
            ) {
                if (connectDevices.isNotEmpty()) {
                    item { SectionHeader(s.discoveryConnectable(connectDevices.size)) }
                    items(connectDevices) { device ->
                        DiscoveredDeviceCard(device = device, label = s.discoveryActionConnect) {
                            viewModel.connectFromDiscovered(device)
                        }
                    }
                }
                if (pairingDevices.isNotEmpty()) {
                    item { SectionHeader(s.discoveryPairable(pairingDevices.size)) }
                    items(pairingDevices) { device ->
                        DiscoveredDeviceCard(device = device, label = s.discoveryActionPair) {
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
                                Text(s.discoveryEmptyTitle, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                                Text(s.discoveryEmptyHint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ── 竖屏：单栏 ──
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = hPadding),
            contentPadding = PaddingValues(top = statusBarPad + 8.dp, bottom = navBarPad + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { topBar() }

            item {
                WearCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = isDiscovering)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (isDiscovering) s.discoveryScanning else s.discoveryStopped,
                            style = MaterialTheme.typography.bodyMedium, color = c.onSurface
                        )
                    }
                }
            }

            item { SectionHeader(s.manualConnectTitle) }
            item { ManualConnectCard(lastHost, lastPort, onConnect = { host, port -> viewModel.connect(host, port) }) }

            if (connectDevices.isNotEmpty()) {
                item { SectionHeader(s.discoveryConnectable(connectDevices.size)) }
                items(connectDevices) { device ->
                    DiscoveredDeviceCard(device = device, label = s.discoveryActionConnect) {
                        viewModel.connectFromDiscovered(device)
                    }
                }
            }

            if (pairingDevices.isNotEmpty()) {
                item { SectionHeader(s.discoveryPairable(pairingDevices.size)) }
                items(pairingDevices) { device ->
                    DiscoveredDeviceCard(device = device, label = s.discoveryActionPair) {
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
                            Text(s.discoveryEmptyTitle, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                            Text(s.discoveryEmptyHint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualConnectCard(
    defaultHost: String,
    defaultPort: Int,
    onConnect: (host: String, port: Int) -> Unit
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    var hostInput by remember(defaultHost) { mutableStateOf(defaultHost) }
    var portInput by remember(defaultPort) { mutableStateOf(defaultPort.toString()) }

    WearCard {
        Text(s.manualConnectTitle, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        AlignedInputColumn(
            labels = listOf(s.labelIp, s.labelPort),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WearInput(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = s.labelIp,
                placeholder = "192.168.1.100",
                modifier = Modifier.fillMaxWidth(),
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            )
            WearInput(
                value = portInput,
                onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                label = s.labelPort,
                placeholder = "5555",
                modifier = Modifier.fillMaxWidth(0.5f)
            )
        }
        Spacer(Modifier.height(12.dp))
        WearButton(
            text = s.btnConnect,
            onClick = { onConnect(hostInput.trim(), portInput.toIntOrNull() ?: 5555) },
            enabled = hostInput.isNotBlank()
        )
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
