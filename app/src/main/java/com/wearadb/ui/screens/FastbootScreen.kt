package com.wearadb.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.fastboot.FastbootConnectionState
import com.wearadb.fastboot.FastbootDevice
import com.wearadb.ui.FastbootViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.theme.WearAdbTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastbootScreen(
    onBack: () -> Unit,
    viewModel: FastbootViewModel = hiltViewModel()
) {
    val colors = WearAdbTheme.colors
    val shape = WearAdbTheme.shape
    val cornerRadius = shape.cornerRadius

    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val fastbootDevices by viewModel.devices.collectAsState()
    val fastbootInfo by viewModel.info.collectAsState()
    val flashProgress by viewModel.flashProgress.collectAsState()
    val connectLog by viewModel.connectLog.collectAsState()

    var oemCommand by remember { mutableStateOf("") }
    var showOemDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }
    var showEraseDialog by remember { mutableStateOf(false) }
    var showBootDialog by remember { mutableStateOf(false) }
    var showStageDialog by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var lastFlashPartition by remember { mutableStateOf("") }

    val s = LocalStrings.current

    // 监听 fastboot 结果
    LaunchedEffect(Unit) {
        viewModel.result.collect { msg ->
            resultMessage = msg
            // 刷入成功后弹出重启确认
            if (msg.contains("成功") && msg.contains("刷入")) {
                showRebootDialog = true
            }
        }
    }

    // 进入页面自动扫描
    LaunchedEffect(Unit) {
        viewModel.scanDevices()
    }

    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.fbModeTitle) },
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
                        Icon(Icons.Default.Refresh, contentDescription = s.btnScan)
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
            // ── 连接状态 ──
            ConnectionStatusCard(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                cornerRadius = cornerRadius,
                onDisconnect = { viewModel.disconnect() }
            )

            // ── 设备列表（未连接时显示）──
            if (connectionState != FastbootConnectionState.CONNECTED) {
                DeviceListCard(
                    devices = fastbootDevices,
                    cornerRadius = cornerRadius,
                    onConnect = { viewModel.connect(it) },
                    onRefresh = { viewModel.scanDevices() }
                )
            }

            // ── 连接日志（连接中或失败时显示）──
            if (connectLog.isNotEmpty() && connectionState != FastbootConnectionState.CONNECTED) {
                ConnectLogCard(
                    log = connectLog,
                    cornerRadius = cornerRadius
                )
            }

            // ── 设备信息（已连接时显示）──
            if (connectionState == FastbootConnectionState.CONNECTED && fastbootInfo.isNotEmpty()) {
                DeviceInfoCard(
                    info = fastbootInfo,
                    cornerRadius = cornerRadius
                )
            }

            // ── 操作按钮（已连接时显示）──
            if (connectionState == FastbootConnectionState.CONNECTED) {
                // 重启操作
                ActionGroupCard(
                    title = s.fbReboot,
                    icon = Icons.Default.PowerSettingsNew,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = s.fbRebootSystem,
                        icon = Icons.Default.RestartAlt,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.reboot() }
                    )
                    ActionButton(
                        label = s.fbRebootRecovery,
                        icon = Icons.Default.Build,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.rebootRecovery() }
                    )
                    ActionButton(
                        label = s.fbRebootBootloader,
                        icon = Icons.Default.DeveloperBoard,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.rebootBootloader() }
                    )
                }

                // 分区操作
                ActionGroupCard(
                    title = s.fbPartitionOps,
                    icon = Icons.Default.Storage,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = s.fbFlashPartition,
                        icon = Icons.Default.FlashOn,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        accent = true,
                        onClick = { showFlashDialog = true }
                    )
                    ActionButton(
                        label = s.fbErasePartition,
                        icon = Icons.Default.DeleteForever,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        danger = true,
                        onClick = { showEraseDialog = true }
                    )
                }

                // OEM 命令
                ActionGroupCard(
                    title = s.fbOemTitle,
                    icon = Icons.Default.Terminal,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = s.fbOemExec,
                        icon = Icons.Default.Code,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { showOemDialog = true }
                    )
                }

                // Bootloader 锁定/解锁
                ActionGroupCard(
                    title = "Bootloader",
                    icon = Icons.Default.Lock,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = s.fbUnlock,
                        icon = Icons.Default.LockOpen,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        danger = true,
                        onClick = { viewModel.flashingUnlock() }
                    )
                    ActionButton(
                        label = s.fbLock,
                        icon = Icons.Default.Lock,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.flashingLock() }
                    )
                }

                // 高级传输
                ActionGroupCard(
                    title = s.fbAdvancedTransfer,
                    icon = Icons.Default.SwapHoriz,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = s.fbTempBoot,
                        icon = Icons.Default.PlayArrow,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        accent = true,
                        onClick = { showBootDialog = true }
                    )
                    ActionButton(
                        label = s.fbStage,
                        icon = Icons.Default.Upload,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { showStageDialog = true }
                    )
                    ActionButton(
                        label = s.fbFetch,
                        icon = Icons.Default.Download,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.fetch() }
                    )
                }

                // 获取所有变量
                ActionGroupCard(
                    title = s.fbDeviceVars,
                    icon = Icons.Default.Info,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = s.fbGetvarAll,
                        icon = Icons.Default.Description,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.loadVarAll() }
                    )
                }

                // 刷入进度
                if (flashProgress >= 0) {
                    FlashProgressCard(
                        progress = flashProgress,
                        cornerRadius = cornerRadius
                    )
                }
            }

            // ── 结果消息 ──
            if (resultMessage != null) {
                ResultCard(
                    message = resultMessage!!,
                    cornerRadius = cornerRadius,
                    onDismiss = { resultMessage = null }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ── 对话框 ──
    if (showOemDialog) {
        OemCommandDialog(
            command = oemCommand,
            onCommandChange = { oemCommand = it },
            onConfirm = {
                viewModel.oem(oemCommand)
                oemCommand = ""
                showOemDialog = false
            },
            onDismiss = { showOemDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showFlashDialog) {
        FlashPartitionDialog(
            onConfirm = { partition, data ->
                lastFlashPartition = partition
                viewModel.flash(partition, data)
                showFlashDialog = false
            },
            onDismiss = { showFlashDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showEraseDialog) {
        ErasePartitionDialog(
            onConfirm = { partition ->
                viewModel.erase(partition)
                showEraseDialog = false
            },
            onDismiss = { showEraseDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showBootDialog) {
        BootImageDialog(
            onConfirm = { data ->
                viewModel.boot(data)
                showBootDialog = false
            },
            onDismiss = { showBootDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showStageDialog) {
        StageDataDialog(
            onConfirm = { data ->
                viewModel.stage(data)
                showStageDialog = false
            },
            onDismiss = { showStageDialog = false },
            cornerRadius = cornerRadius
        )
    }

    // ── 刷入成功后重启确认 ──
    if (showRebootDialog) {
        val colors = WearAdbTheme.colors
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            containerColor = colors.surface,
            titleContentColor = colors.onSurface,
            textContentColor = colors.onSurfaceDim,
            title = { Text(s.fbFlashSuccessTitle) },
            text = {
                Text(
                    s.fbFlashSuccessMsg(lastFlashPartition),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRebootDialog = false
                    viewModel.rebootRecovery()
                }) {
                    Text(s.fbRebootRecovery, color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(s.fbStayFastboot, color = colors.onSurfaceDim)
                }
            },
            shape = RoundedCornerShape(cornerRadius)
        )
    }
}

// ── 组件 ──

@Composable
private fun ConnectionStatusCard(
    connectionState: FastbootConnectionState,
    connectedDevice: FastbootDevice?,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onDisconnect: () -> Unit
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    val statusColor = when (connectionState) {
        FastbootConnectionState.CONNECTED -> colors.statusDotActive
        FastbootConnectionState.CONNECTING -> colors.warning
        FastbootConnectionState.ERROR -> colors.error
        FastbootConnectionState.DISCONNECTED -> colors.statusDotInactive
    }
    val statusText = when (connectionState) {
        FastbootConnectionState.CONNECTED -> "${s.statusConnected}: ${connectedDevice?.displayName ?: ""}"
        FastbootConnectionState.CONNECTING -> s.statusConnecting
        FastbootConnectionState.ERROR -> s.statusError
        FastbootConnectionState.DISCONNECTED -> "${s.statusDisconnected} — ${s.fbUsbHint}"
    }

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (connectionState == FastbootConnectionState.CONNECTED) {
                    TextButton(onClick = onDisconnect) {
                        Text(s.btnDisconnect, color = colors.error)
                    }
                }
            }
            if (connectionState == FastbootConnectionState.CONNECTING) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    s.fbUsbPermission,
                    color = colors.onSurfaceDim,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DeviceListCard(
    devices: List<FastbootDevice>,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onConnect: (FastbootDevice) -> Unit,
    onRefresh: () -> Unit
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current

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
                Text(
                    s.fbDevicesHeader,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, s.btnRefresh, tint = colors.iconTint, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    s.fbEmptyHint,
                    color = colors.onSurfaceDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            } else {
                devices.forEach { device ->
                    FastbootDeviceItem(
                        device = device,
                        cornerRadius = cornerRadius,
                        onClick = { onConnect(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FastbootDeviceItem(
    device: FastbootDevice,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius / 2))
            .border(1.dp, colors.border, RoundedCornerShape(cornerRadius / 2))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DeveloperBoard,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.displayName,
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (device.productName.isNotEmpty()) {
                Text(
                    s.fbSerial(device.serialNumber),
                    color = colors.onSurfaceDim,
                    fontSize = 12.sp
                )
            }
        }
        Icon(Icons.Default.ChevronRight, s.btnConnect, tint = colors.iconTint)
    }
}

@Composable
private fun DeviceInfoCard(
    info: Map<String, String>,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.deviceInfoTitle,
                color = colors.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            info.forEach { (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Text(
                        key,
                        color = colors.onSurfaceDim,
                        fontSize = 13.sp,
                        modifier = Modifier.width(140.dp)
                    )
                    Text(
                        value,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionGroupCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cornerRadius: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = WearAdbTheme.colors

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: com.wearadb.ui.theme.WearAdbColors,
    cornerRadius: androidx.compose.ui.unit.Dp,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = when {
        danger -> colors.buttonDanger
        accent -> colors.buttonPrimary
        else -> colors.buttonSecondary
    }
    val textColor = when {
        danger -> colors.buttonDangerText
        accent -> colors.buttonPrimaryText
        else -> colors.buttonSecondaryText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius / 2))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = textColor, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = textColor.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FlashProgressCard(
    progress: Int,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(s.fbFlashing, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text("$progress%", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = colors.accent,
                trackColor = colors.surfaceAlt
            )
        }
    }
}

@Composable
private fun ConnectLogCard(
    log: String,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    val hasError = log.contains("[错误]") || log.contains("失败")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasError) Icons.Default.Error else Icons.Default.Description,
                    null,
                    tint = if (hasError) colors.error else colors.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    s.fbConnectLog,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                log,
                color = if (hasError) colors.error else colors.onSurfaceDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ResultCard(
    message: String,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    val isError = message.contains("失败") || message.contains("错误") || message.contains("Error")
    val bgColor = if (isError) colors.error.copy(alpha = 0.1f) else colors.accent.copy(alpha = 0.1f)
    val borderColor = if (isError) colors.error.copy(alpha = 0.3f) else colors.accent.copy(alpha = 0.3f)
    val textColor = if (isError) colors.error else colors.onSurface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                message,
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, s.btnClose, tint = colors.iconTint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── 对话框 ──

@Composable
private fun OemCommandDialog(
    command: String,
    onCommandChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text(s.fbOemDialogTitle) },
        text = {
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                label = { Text(s.fbCmdLabel, color = colors.label) },
                placeholder = { Text(s.fbCmdPlaceholder, color = colors.onSurfaceDim) },
                singleLine = true,
                shape = RoundedCornerShape(cornerRadius / 2),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    cursorColor = colors.accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = command.isNotBlank()
            ) {
                Text(s.fbExecute, color = if (command.isNotBlank()) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.btnCancel, color = colors.onSurfaceDim)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErasePartitionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    var partition by remember { mutableStateOf("") }

    val erasePartitions = listOf("userdata", "cache", "dalvik_cache", "metadata", "misc")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text(s.fbEraseTitle) },
        text = {
            Column {
                Text(
                    s.fbEraseWarning,
                    color = colors.warning,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(s.fbSelectPartition, color = colors.onSurfaceDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    erasePartitions.forEach { name ->
                        val selected = partition == name
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(cornerRadius / 2))
                                .background(if (selected) colors.error.copy(alpha = 0.15f) else colors.surfaceAlt)
                                .border(
                                    1.dp,
                                    if (selected) colors.error else colors.border,
                                    RoundedCornerShape(cornerRadius / 2)
                                )
                                .clickable { partition = name }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                name,
                                color = if (selected) colors.error else colors.onSurface,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = partition,
                    onValueChange = { partition = it },
                    label = { Text(s.fbPartitionName, color = colors.label) },
                    placeholder = { Text(s.fbManualInput, color = colors.onSurfaceDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(cornerRadius / 2),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        cursorColor = colors.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(partition) },
                enabled = partition.isNotBlank()
            ) {
                Text(s.fbEraseConfirm, color = if (partition.isNotBlank()) colors.error else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.btnCancel, color = colors.onSurfaceDim)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashPartitionDialog(
    onConfirm: (String, ByteArray) -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    val context = LocalContext.current
    var partition by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<java.io.File?>(null) }

    // 文件选择器
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // 复制到缓存目录
            val inputStream = context.contentResolver.openInputStream(it)
            val tmpFile = java.io.File(context.cacheDir, "fastboot_flash_${System.currentTimeMillis()}")
            inputStream?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            selectedFile = tmpFile
        }
    }

    val presetPartitions = listOf("recovery", "boot", "vendor_boot", "dtbo", "vbmeta", "system", "super", "modem", "persist")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text(s.fbFlashTitle) },
        text = {
            Column {
                Text(s.fbSelectPartition, color = colors.onSurfaceDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetPartitions.forEach { name ->
                        val selected = partition == name
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(cornerRadius / 2))
                                .background(if (selected) colors.accent else colors.surfaceAlt)
                                .border(
                                    1.dp,
                                    if (selected) colors.accent else colors.border,
                                    RoundedCornerShape(cornerRadius / 2)
                                )
                                .clickable { partition = name }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                name,
                                color = if (selected) colors.buttonPrimaryText else colors.onSurface,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = partition,
                    onValueChange = { partition = it },
                    label = { Text(s.fbPartitionName, color = colors.label) },
                    placeholder = { Text(s.fbManualInput, color = colors.onSurfaceDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(cornerRadius / 2),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        cursorColor = colors.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 文件选择
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(cornerRadius / 2))
                        .border(1.dp, colors.border, RoundedCornerShape(cornerRadius / 2))
                        .clickable { launcher.launch("*/*") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FileOpen, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedFile?.let { "${it.name} (${it.length() / 1024}KB)" } ?: s.fbSelectImage,
                        color = if (selectedFile != null) colors.onSurface else colors.onSurfaceDim,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            val canConfirm = partition.isNotBlank() && selectedFile != null
            TextButton(
                onClick = {
                    selectedFile?.let { file ->
                        onConfirm(partition, file.readBytes())
                    }
                },
                enabled = canConfirm
            ) {
                Text(s.fbFlashConfirm, color = if (canConfirm) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.btnCancel, color = colors.onSurfaceDim)
            }
        }
    )
}

@Composable
private fun BootImageDialog(
    onConfirm: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<java.io.File?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val tmpFile = java.io.File(context.cacheDir, "fastboot_boot_${System.currentTimeMillis()}")
            inputStream?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            selectedFile = tmpFile
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text(s.fbTempBootTitle) },
        text = {
            Column {
                Text(
                    s.fbTempBootWarning,
                    color = colors.warning,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(cornerRadius / 2))
                        .border(1.dp, colors.border, RoundedCornerShape(cornerRadius / 2))
                        .clickable { launcher.launch("*/*") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FileOpen, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedFile?.let { "${it.name} (${it.length() / 1024}KB)" } ?: s.fbSelectBoot,
                        color = if (selectedFile != null) colors.onSurface else colors.onSurfaceDim,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedFile?.let { onConfirm(it.readBytes()) } },
                enabled = selectedFile != null
            ) {
                Text(s.fbBootConfirm, color = if (selectedFile != null) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.btnCancel, color = colors.onSurfaceDim)
            }
        }
    )
}

@Composable
private fun StageDataDialog(
    onConfirm: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    val s = LocalStrings.current
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<java.io.File?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val tmpFile = java.io.File(context.cacheDir, "fastboot_stage_${System.currentTimeMillis()}")
            inputStream?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            selectedFile = tmpFile
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text(s.fbStageTitle) },
        text = {
            Column {
                Text(
                    s.fbStageDesc,
                    color = colors.onSurfaceDim,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(cornerRadius / 2))
                        .border(1.dp, colors.border, RoundedCornerShape(cornerRadius / 2))
                        .clickable { launcher.launch("*/*") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FileOpen, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedFile?.let { "${it.name} (${it.length() / 1024}KB)" } ?: s.fbSelectUpload,
                        color = if (selectedFile != null) colors.onSurface else colors.onSurfaceDim,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedFile?.let { onConfirm(it.readBytes()) } },
                enabled = selectedFile != null
            ) {
                Text(s.fbStageConfirm, color = if (selectedFile != null) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.btnCancel, color = colors.onSurfaceDim)
            }
        }
    )
}
