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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.R
import com.wearadb.data.repository.ConnectionState
import com.wearadb.data.model.SavedDevice
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
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
    onNavigateToFastboot: () -> Unit,
    onNavigateToUsbAdb: () -> Unit = {},
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val connectionState by viewModel.connectionState.collectAsState()
    val deviceBanner by viewModel.deviceBanner.collectAsState()
    val devices by viewModel.devices.collectAsState(initial = emptyList())
    val lastHost by viewModel.lastHost.collectAsState()
    val lastPort by viewModel.lastPort.collectAsState()
    val showBluetoothDialog by viewModel.showBluetoothDialog.collectAsState()

    val expanded = useDualPane()
    val hPadding = adaptiveHorizontalPadding()

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var hostInput by remember(lastHost) { mutableStateOf(lastHost) }
    var portInput by remember(lastPort) { mutableStateOf(lastPort.toString()) }
    val isConnecting by remember(connectionState) {
        derivedStateOf { connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.AUTHENTICATING }
    }
    val isConnected by remember(connectionState) {
        derivedStateOf { connectionState == ConnectionState.CONNECTED }
    }

    // 预取字符串，避免在 LazyColumn item lambda 中调用
    val s = LocalStrings.current

    Scaffold(containerColor = c.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(c.background)
                    .padding(horizontal = hPadding)
                    .padding(top = statusBarPad + 16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = navBarPad + 16.dp)
                ) {
                    item {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text("wear-adb", style = MaterialTheme.typography.displayLarge, color = c.onBackground)
                            Text(s.homeSubtitle, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        }
                    }
                    item { WirelessConnectionCard(connectionState, isConnecting, isConnected, deviceBanner, hostInput, portInput, lastHost, lastPort, onHostChange = { hostInput = it }, onPortChange = { portInput = it }, onConnect = { viewModel.connect(hostInput.trim(), portInput.toIntOrNull() ?: 5555) }, onDisconnect = { viewModel.disconnect() }, onNavigateToDiscovery, onNavigateToPairing) }
                    if (isConnected) {
                        item { SectionHeader(s.sectionTools) }
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FeatureCard(Icons.Outlined.Terminal, s.featureShell, s.featureShellDesc, Modifier.weight(1f), onNavigateToShell)
                                FeatureCard(Icons.Outlined.PhoneAndroid, s.featureDevice, s.featureDeviceDesc, Modifier.weight(1f), onNavigateToDeviceInfo)
                            }
                        }
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FeatureCard(Icons.Outlined.Apps, s.featureApps, s.featureAppsDesc, Modifier.weight(1f), onNavigateToApps)
                                FeatureCard(Icons.Outlined.Folder, s.featureFiles, s.featureFilesDesc, Modifier.weight(1f), onNavigateToFiles)
                            }
                        }
                        item {
                            FeatureCard(Icons.Outlined.Build, s.featureAdvanced, s.featureAdvancedDesc, Modifier.fillMaxWidth(), onNavigateToAdvanced)
                        }
                    }
                    if (devices.isNotEmpty()) {
                        item { SectionHeader(s.sectionHistory) }
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

                Spacer(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = navBarPad + 16.dp)
                ) {
                    item { SectionHeader(s.sectionUsbDebug) }
                    item {
                        WearCard {
                            Text(s.usbDebugDesc, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FeatureCard(Icons.Outlined.DeveloperBoard, s.featureFastboot, s.featureFastbootDesc, Modifier.weight(1f), onNavigateToFastboot)
                                FeatureCard(Icons.Outlined.Usb, s.featureUsbAdb, s.featureUsbAdbDesc, Modifier.weight(1f), onNavigateToUsbAdb)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(c.background)
                    .padding(horizontal = hPadding),
                contentPadding = PaddingValues(
                    top = statusBarPad + 16.dp,
                    bottom = navBarPad + 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("wear-adb", style = MaterialTheme.typography.displayLarge, color = c.onBackground)
                        Text(s.homeSubtitle, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    }
                }
                item { WirelessConnectionCard(connectionState, isConnecting, isConnected, deviceBanner, hostInput, portInput, lastHost, lastPort, onHostChange = { hostInput = it }, onPortChange = { portInput = it }, onConnect = { viewModel.connect(hostInput.trim(), portInput.toIntOrNull() ?: 5555) }, onDisconnect = { viewModel.disconnect() }, onNavigateToDiscovery, onNavigateToPairing) }
                if (isConnected) {
                    item { SectionHeader(s.sectionTools) }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard(Icons.Outlined.Terminal, s.featureShell, s.featureShellDesc, Modifier.weight(1f), onNavigateToShell)
                            FeatureCard(Icons.Outlined.PhoneAndroid, s.featureDevice, s.featureDeviceDesc, Modifier.weight(1f), onNavigateToDeviceInfo)
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard(Icons.Outlined.Apps, s.featureApps, s.featureAppsDesc, Modifier.weight(1f), onNavigateToApps)
                            FeatureCard(Icons.Outlined.Folder, s.featureFiles, s.featureFilesDesc, Modifier.weight(1f), onNavigateToFiles)
                        }
                    }
                    item {
                        FeatureCard(Icons.Outlined.Build, s.featureAdvanced, s.featureAdvancedDesc, Modifier.fillMaxWidth(), onNavigateToAdvanced)
                    }
                }
                item {
                    WearCard {
                        Text(s.usbDebugDesc, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FeatureCard(Icons.Outlined.DeveloperBoard, s.featureFastboot, s.featureFastbootDesc, Modifier.weight(1f), onNavigateToFastboot)
                            FeatureCard(Icons.Outlined.Usb, s.featureUsbAdb, s.featureUsbAdbDesc, Modifier.weight(1f), onNavigateToUsbAdb)
                        }
                    }
                }
                if (devices.isNotEmpty()) {
                    item { SectionHeader(s.sectionHistory) }
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

    if (showBluetoothDialog) {
        val cr = WearAdbTheme.shape.cornerRadius
        AlertDialog(
            onDismissRequest = { viewModel.dismissBluetoothDialog() },
            containerColor = c.surface,
            shape = RoundedCornerShape(cr),
            title = { Text(s.btTitle, style = MaterialTheme.typography.titleMedium, color = c.onSurface) },
            text = { Text(s.btMessage, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) },
            confirmButton = { TextButton(onClick = { viewModel.confirmDisableBluetooth() }) { Text(s.btConfirm, color = c.accent) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissBluetoothDialog() }) { Text(s.btDismiss, color = c.onSurfaceVariant) } }
        )
    }
}

@Composable
private fun WirelessConnectionCard(
    connectionState: ConnectionState,
    isConnecting: Boolean,
    isConnected: Boolean,
    deviceBanner: String,
    hostInput: String,
    portInput: String,
    lastHost: String,
    lastPort: Int,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToPairing: () -> Unit
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    WearCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            StatusDot(active = isConnected)
            Spacer(Modifier.width(10.dp))
            Text(
                text = when (connectionState) {
                    ConnectionState.DISCONNECTED -> s.statusDisconnected
                    ConnectionState.CONNECTING -> s.statusConnecting
                    ConnectionState.AUTHENTICATING -> s.statusAuth
                    ConnectionState.CONNECTED -> s.statusConnected
                    ConnectionState.ERROR -> s.statusError
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
        AlignedInputColumn(
            labels = listOf(s.labelIp, s.labelPort),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WearInput(
                value = hostInput,
                onValueChange = onHostChange,
                label = s.labelIp,
                placeholder = "192.168.1.100",
                modifier = Modifier.fillMaxWidth(),
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            )
            WearInput(
                value = portInput,
                onValueChange = { onPortChange(it.filter { ch -> ch.isDigit() }) },
                label = s.labelPort,
                placeholder = "5555",
                modifier = Modifier.fillMaxWidth(0.5f)
            )
        }
        Spacer(Modifier.height(16.dp))
        if (isConnected) {
            WearButton(text = s.btnDisconnect, onClick = onDisconnect, variant = ButtonVariant.Danger)
        } else {
            WearButton(
                text = if (isConnecting) s.btnConnecting else s.btnConnect,
                onClick = onConnect,
                enabled = hostInput.isNotBlank() && !isConnecting
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WearButton(
                    text = s.btnDiscover,
                    onClick = onNavigateToDiscovery,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Secondary
                )
                WearButton(
                    text = s.btnPair,
                    onClick = onNavigateToPairing,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Secondary
                )
            }
        }
        AnimatedVisibility(visible = connectionState == ConnectionState.ERROR) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(s.errorConnect, style = MaterialTheme.typography.bodySmall, color = c.error)
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
            .fillMaxHeight()
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
    val s = LocalStrings.current
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
                    s.actionFavorite,
                    tint = if (device.isFavorite) c.accent else c.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, s.actionDelete, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
