package com.wearadb.ui.screens

import androidx.compose.animation.*
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
import com.wearadb.ui.AppViewModel
import com.wearadb.ui.theme.WearAdbTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastbootScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = hiltViewModel()
) {
    val colors = WearAdbTheme.colors
    val shape = WearAdbTheme.shape
    val cornerRadius = shape.cornerRadius

    val connectionState by viewModel.fastbootConnectionState.collectAsState()
    val connectedDevice by viewModel.fastbootConnectedDevice.collectAsState()
    val fastbootDevices by viewModel.fastbootDevices.collectAsState()
    val fastbootInfo by viewModel.fastbootInfo.collectAsState()
    val flashProgress by viewModel.fastbootFlashProgress.collectAsState()

    var oemCommand by remember { mutableStateOf("") }
    var showOemDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }
    var showEraseDialog by remember { mutableStateOf(false) }
    var showBootDialog by remember { mutableStateOf(false) }
    var showStageDialog by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // 监听 fastboot 结果
    LaunchedEffect(Unit) {
        viewModel.fastbootResult.collect { msg ->
            resultMessage = msg
        }
    }

    // 进入页面自动扫描
    LaunchedEffect(Unit) {
        viewModel.scanFastbootDevices()
    }

    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fastboot 模式") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnectFastboot()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.scanFastbootDevices() }) {
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
            // ── 连接状态 ──
            ConnectionStatusCard(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                cornerRadius = cornerRadius,
                onDisconnect = { viewModel.disconnectFastboot() }
            )

            // ── 设备列表（未连接时显示）──
            if (connectionState != FastbootConnectionState.CONNECTED) {
                DeviceListCard(
                    devices = fastbootDevices,
                    cornerRadius = cornerRadius,
                    onConnect = { viewModel.connectFastboot(it) },
                    onRefresh = { viewModel.scanFastbootDevices() }
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
                    title = "重启",
                    icon = Icons.Default.PowerSettingsNew,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = "重启到系统",
                        icon = Icons.Default.RestartAlt,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.fastbootReboot() }
                    )
                    ActionButton(
                        label = "重启到 Recovery",
                        icon = Icons.Default.Build,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.fastbootRebootRecovery() }
                    )
                    ActionButton(
                        label = "重启到 Bootloader",
                        icon = Icons.Default.DeveloperBoard,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.fastbootRebootBootloader() }
                    )
                }

                // 分区操作
                ActionGroupCard(
                    title = "分区操作",
                    icon = Icons.Default.Storage,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = "刷入分区...",
                        icon = Icons.Default.FlashOn,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        accent = true,
                        onClick = { showFlashDialog = true }
                    )
                    ActionButton(
                        label = "擦除分区...",
                        icon = Icons.Default.DeleteForever,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        danger = true,
                        onClick = { showEraseDialog = true }
                    )
                }

                // OEM 命令
                ActionGroupCard(
                    title = "OEM 命令",
                    icon = Icons.Default.Terminal,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = "执行 OEM 命令...",
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
                        label = "解锁 Bootloader",
                        icon = Icons.Default.LockOpen,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        danger = true,
                        onClick = { viewModel.fastbootFlashingUnlock() }
                    )
                    ActionButton(
                        label = "锁定 Bootloader",
                        icon = Icons.Default.Lock,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.fastbootFlashingLock() }
                    )
                }

                // 高级传输
                ActionGroupCard(
                    title = "高级传输",
                    icon = Icons.Default.SwapHoriz,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = "临时启动镜像...",
                        icon = Icons.Default.PlayArrow,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        accent = true,
                        onClick = { showBootDialog = true }
                    )
                    ActionButton(
                        label = "上传数据到设备 (stage)...",
                        icon = Icons.Default.Upload,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { showStageDialog = true }
                    )
                    ActionButton(
                        label = "从设备下载数据 (fetch)",
                        icon = Icons.Default.Download,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.fastbootFetch() }
                    )
                }

                // 获取所有变量
                ActionGroupCard(
                    title = "设备变量",
                    icon = Icons.Default.Info,
                    cornerRadius = cornerRadius
                ) {
                    ActionButton(
                        label = "获取所有变量 (getvar all)",
                        icon = Icons.Default.ListAlt,
                        colors = colors,
                        cornerRadius = cornerRadius,
                        onClick = { viewModel.loadFastbootVarAll() }
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
                viewModel.fastbootOem(oemCommand)
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
                viewModel.fastbootFlash(partition, data)
                showFlashDialog = false
            },
            onDismiss = { showFlashDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showEraseDialog) {
        ErasePartitionDialog(
            onConfirm = { partition ->
                viewModel.fastbootErase(partition)
                showEraseDialog = false
            },
            onDismiss = { showEraseDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showBootDialog) {
        BootImageDialog(
            onConfirm = { data ->
                viewModel.fastbootBoot(data)
                showBootDialog = false
            },
            onDismiss = { showBootDialog = false },
            cornerRadius = cornerRadius
        )
    }

    if (showStageDialog) {
        StageDataDialog(
            onConfirm = { data ->
                viewModel.fastbootStage(data)
                showStageDialog = false
            },
            onDismiss = { showStageDialog = false },
            cornerRadius = cornerRadius
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
    val statusColor = when (connectionState) {
        FastbootConnectionState.CONNECTED -> colors.statusDotActive
        FastbootConnectionState.CONNECTING -> colors.warning
        FastbootConnectionState.ERROR -> colors.error
        FastbootConnectionState.DISCONNECTED -> colors.statusDotInactive
    }
    val statusText = when (connectionState) {
        FastbootConnectionState.CONNECTED -> "已连接: ${connectedDevice?.displayName ?: ""}"
        FastbootConnectionState.CONNECTING -> "连接中..."
        FastbootConnectionState.ERROR -> "连接失败"
        FastbootConnectionState.DISCONNECTED -> "未连接 — 请用 USB 线连接处于 Fastboot 模式的设备"
    }

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    Text("断开", color = colors.error)
                }
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
                    "检测到的 Fastboot 设备",
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "刷新", tint = colors.iconTint, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    "未检测到 Fastboot 设备。\n请确认设备已进入 Bootloader/Fastboot 模式并用 USB 线连接。",
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
                    "序列号: ${device.serialNumber}",
                    color = colors.onSurfaceDim,
                    fontSize = 12.sp
                )
            }
        }
        Icon(Icons.Default.ChevronRight, "连接", tint = colors.iconTint)
    }
}

@Composable
private fun DeviceInfoCard(
    info: Map<String, String>,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "设备信息",
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
                        modifier = Modifier.width(120.dp)
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

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("刷入中...", color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
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
private fun ResultCard(
    message: String,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit
) {
    val colors = WearAdbTheme.colors
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
                Icon(Icons.Default.Close, "关闭", tint = colors.iconTint, modifier = Modifier.size(16.dp))
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text("执行 OEM 命令") },
        text = {
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                label = { Text("命令", color = colors.label) },
                placeholder = { Text("例如: unlock", color = colors.onSurfaceDim) },
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
                Text("执行", color = if (command.isNotBlank()) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.onSurfaceDim)
            }
        }
    )
}

@Composable
private fun ErasePartitionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
    var partition by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text("擦除分区") },
        text = {
            Column {
                Text(
                    "⚠️ 此操作不可逆，请确认分区名称正确。",
                    color = colors.warning,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = partition,
                    onValueChange = { partition = it },
                    label = { Text("分区名", color = colors.label) },
                    placeholder = { Text("例如: userdata, cache, system", color = colors.onSurfaceDim) },
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
                Text("擦除", color = if (partition.isNotBlank()) colors.error else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.onSurfaceDim)
            }
        }
    )
}

@Composable
private fun FlashPartitionDialog(
    onConfirm: (String, ByteArray) -> Unit,
    onDismiss: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    val colors = WearAdbTheme.colors
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceDim,
        title = { Text("刷入分区") },
        text = {
            Column {
                OutlinedTextField(
                    value = partition,
                    onValueChange = { partition = it },
                    label = { Text("分区名", color = colors.label) },
                    placeholder = { Text("例如: boot, recovery, system", color = colors.onSurfaceDim) },
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
                        text = selectedFile?.let { "${it.name} (${it.length() / 1024}KB)" } ?: "选择镜像文件...",
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
                Text("刷入", color = if (canConfirm) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.onSurfaceDim)
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
        title = { Text("临时启动镜像") },
        text = {
            Column {
                Text(
                    "⚠️ 此操作会临时启动选中的镜像，不会写入分区。设备重启后恢复原系统。",
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
                        text = selectedFile?.let { "${it.name} (${it.length() / 1024}KB)" } ?: "选择 boot 镜像...",
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
                Text("启动", color = if (selectedFile != null) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.onSurfaceDim)
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
        title = { Text("上传数据到设备") },
        text = {
            Column {
                Text(
                    "将文件上传到设备内存（stage），可配合 OEM 命令使用。",
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
                        text = selectedFile?.let { "${it.name} (${it.length() / 1024}KB)" } ?: "选择要上传的文件...",
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
                Text("上传", color = if (selectedFile != null) colors.accent else colors.onSurfaceDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.onSurfaceDim)
            }
        }
    )
}
