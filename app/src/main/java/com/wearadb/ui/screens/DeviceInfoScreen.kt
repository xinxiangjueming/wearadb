package com.wearadb.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.data.model.DeviceInfo
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding
import com.wearadb.ui.utils.formatBytes
import com.wearadb.ui.utils.isLandscape

@Composable
fun DeviceInfoScreen(
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val info by viewModel.deviceInfo.collectAsState()
    val loading by viewModel.deviceInfoLoading.collectAsState()
    val usbState by viewModel.usbAdbConnectionState.collectAsState()
    val wirelessState by viewModel.connectionState.collectAsState()

    // Load on first entry, and re-load if data is null but connection is active
    LaunchedEffect(Unit, usbState, wirelessState) {
        if (info == null) {
            viewModel.loadDeviceInfo(force = true)
        }
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hPadding = adaptiveHorizontalPadding()
    val landscape = isLandscape()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = hPadding),
        contentPadding = PaddingValues(top = statusBarPad + 8.dp, bottom = navBarPad + 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "topbar") {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.onBackground)
                }
                Spacer(Modifier.width(8.dp))
                Text("设备信息", style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.loadDeviceInfo(force = true) }) {
                    Icon(Icons.Outlined.Refresh, "刷新", tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (loading) {
            item(key = "loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                }
            }
            return@LazyColumn
        }

        val d = info ?: return@LazyColumn

        if (landscape) {
            // ── 横屏：基本信息+系统 并排 ──
            item(key = "row_basic_system") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader("基本信息")
                        WearCard {
                            InfoRow("品牌", d.brand); InfoRow("型号", d.model); InfoRow("设备代号", d.device)
                            InfoRow("序列号", d.serialno); InfoRow("ABI", d.abi)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader("系统")
                        WearCard {
                            InfoRow("Android 版本", d.androidVersion); InfoRow("SDK", d.sdkVersion)
                            InfoRow("Build ID", d.buildId); InfoRow("指纹", d.fingerprint, mono = true)
                        }
                    }
                }
            }
            // ── 横屏：屏幕+电池 并排 ──
            if (d.screenWidth > 0 || d.batteryLevel >= 0) {
                item(key = "row_screen_battery") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (d.screenWidth > 0) {
                            Column(modifier = Modifier.weight(1f)) {
                                SectionHeader("屏幕")
                                WearCard {
                                    InfoRow("分辨率", "${d.screenWidth} × ${d.screenHeight}"); InfoRow("DPI", d.density.toString())
                                }
                            }
                        }
                        if (d.batteryLevel >= 0) {
                            Column(modifier = Modifier.weight(1f)) {
                                SectionHeader("电池")
                                WearCard {
                                    if (d.batteryDesignCapacity > 0) InfoRow("设计容量", "${d.batteryDesignCapacity} mAh")
                                    if (d.batteryTechnology.isNotEmpty()) InfoRow("电池类型", d.batteryTechnology)
                                    if (d.batteryHealth.isNotEmpty()) InfoRow("健康状态", d.batteryHealth)
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = when {
                                                d.batteryLevel > 80 -> Icons.Outlined.BatteryFull
                                                d.batteryLevel > 30 -> Icons.Outlined.Battery5Bar
                                                else -> Icons.Outlined.Battery1Bar
                                            },
                                            contentDescription = null,
                                            tint = when {
                                                d.batteryLevel > 50 -> c.accent
                                                d.batteryLevel > 20 -> c.warning
                                                else -> c.error
                                            },
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            InfoRow("电量", "${d.batteryLevel}%")
                                            InfoRow("状态", d.batteryStatus)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ── 竖屏：逐行排列 ──
            item(key = "hdr_basic") { SectionHeader("基本信息") }
            item(key = "card_basic") {
                WearCard {
                    InfoRow("品牌", d.brand); InfoRow("型号", d.model); InfoRow("设备代号", d.device)
                    InfoRow("序列号", d.serialno); InfoRow("ABI", d.abi)
                }
            }
            item(key = "hdr_system") { SectionHeader("系统") }
            item(key = "card_system") {
                WearCard {
                    InfoRow("Android 版本", d.androidVersion); InfoRow("SDK", d.sdkVersion)
                    InfoRow("Build ID", d.buildId); InfoRow("指纹", d.fingerprint, mono = true)
                }
            }
            if (d.screenWidth > 0) {
                item(key = "hdr_screen") { SectionHeader("屏幕") }
                item(key = "card_screen") { WearCard { InfoRow("分辨率", "${d.screenWidth} × ${d.screenHeight}"); InfoRow("DPI", d.density.toString()) } }
            }
            if (d.batteryLevel >= 0) {
                item(key = "hdr_battery") { SectionHeader("电池") }
                item(key = "card_battery") {
                    WearCard {
                        if (d.batteryDesignCapacity > 0) InfoRow("设计容量", "${d.batteryDesignCapacity} mAh")
                        if (d.batteryCurrentCapacity > 0) InfoRow("当前容量", "${d.batteryCurrentCapacity} mAh")
                        if (d.batteryTechnology.isNotEmpty()) InfoRow("电池类型", d.batteryTechnology)
                        if (d.batteryHealth.isNotEmpty()) InfoRow("健康状态", d.batteryHealth)
                        if (d.batteryVoltage > 0) InfoRow("电压", "${"%.2f".format(d.batteryVoltage / 1000.0)} V")
                        if (d.batteryTemperature > 0) InfoRow("温度", "${"%.1f".format(d.batteryTemperature / 10.0)}°C")
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = c.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        InfoRow("电量", "${d.batteryLevel}%")
                        InfoRow("状态", d.batteryStatus)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    d.batteryLevel > 80 -> Icons.Outlined.BatteryFull
                                    d.batteryLevel > 30 -> Icons.Outlined.Battery5Bar
                                    else -> Icons.Outlined.Battery1Bar
                                },
                                contentDescription = null,
                                tint = when {
                                    d.batteryLevel > 50 -> c.accent
                                    d.batteryLevel > 20 -> c.warning
                                    else -> c.error
                                },
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                BatteryBar(fraction = d.batteryLevel / 100f)
                            }
                        }
                    }
                }
            }
        }

        // ── 内存与存储（横竖屏共用，全宽） ──
        item(key = "hdr_mem") { SectionHeader("内存与存储") }
        item(key = "card_mem") {
            val memTotalBytes = remember(d.memTotal) { parseMemBytes(d.memTotal) }
            val memAvailBytes = remember(d.memAvail) { parseMemBytes(d.memAvail) }
            WearCard {
                Text("运行内存", style = MaterialTheme.typography.labelLarge, color = c.accent)
                Spacer(Modifier.height(4.dp))
                if (memTotalBytes > 0) {
                    val memUsedBytes = memTotalBytes - memAvailBytes
                    val memFraction = remember(memTotalBytes, memAvailBytes) { memUsedBytes.toFloat() / memTotalBytes }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoRow("已用", formatBytes(memUsedBytes))
                            InfoRow("可用", formatBytes(memAvailBytes))
                            InfoRow("总量", formatBytes(memTotalBytes))
                        }
                        Text("${(memFraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    StorageProgressBar(fraction = memFraction)
                } else {
                    Text("暂无数据", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = c.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text("内部存储", style = MaterialTheme.typography.labelLarge, color = c.accent)
                Spacer(Modifier.height(4.dp))
                if (d.storageTotal > 0) {
                    val fraction = remember(d.storageUsed, d.storageTotal) { d.storageUsed.toFloat() / d.storageTotal }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoRow("已用", formatBytes(d.storageUsed))
                            InfoRow("可用", formatBytes(d.storageFree))
                            InfoRow("总量", formatBytes(d.storageTotal))
                        }
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    StorageProgressBar(fraction = fraction)
                } else {
                    Text("暂无数据，请稍后刷新", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StorageProgressBar(fraction: Float) {
    val c = WearAdbTheme.colors
    val shape = remember { RoundedCornerShape(4.dp) }
    val barColor = when {
        fraction > 0.9f -> c.error
        fraction > 0.75f -> c.warning
        else -> c.accent
    }
    Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(shape).background(c.surface)) {
        Box(
            modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(barColor)
        )
    }
}

@Composable
private fun BatteryBar(fraction: Float) {
    val c = WearAdbTheme.colors
    val shape = remember { RoundedCornerShape(4.dp) }
    val barColor = when {
        fraction > 0.5f -> c.accent
        fraction > 0.2f -> c.warning
        else -> c.error
    }
    Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(shape).background(c.surface)) {
        Box(
            modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(barColor)
        )
    }
}

private fun parseMemBytes(value: String): Long {
    if (value.isEmpty()) return 0
    val parts = value.trim().split("\\s+".toRegex())
    val num = parts.getOrNull(0)?.toLongOrNull() ?: return 0
    val unit = parts.getOrNull(1)?.lowercase() ?: ""
    return when (unit) {
        "kb" -> num * 1024
        "mb" -> num * 1024 * 1024
        "gb" -> num * 1024 * 1024 * 1024
        else -> num
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    val c = WearAdbTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(
            value.ifEmpty { "-" },
            style = if (mono) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = c.onSurface,
            modifier = Modifier.weight(0.65f)
        )
    }
}
