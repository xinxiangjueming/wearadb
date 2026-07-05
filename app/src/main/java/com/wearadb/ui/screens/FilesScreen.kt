package com.wearadb.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import java.util.zip.ZipInputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.data.model.FileEntry
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.utils.formatBytes
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.useDualPane
import com.wearadb.ui.utils.adaptiveHorizontalPadding

@Composable
fun FilesScreen(
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    val context = LocalContext.current
    val files by viewModel.files.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val loading by viewModel.filesLoading.collectAsState()

    // recomposition tracking
    var recompositionCount = remember { 0 }
    SideEffect {
        recompositionCount++
        if (recompositionCount % 5 == 0 || recompositionCount <= 2) {
            android.util.Log.d("FilesScreen", "recomposition #$recompositionCount, files=${files.size}, path=$currentPath")
        }
    }
    var selectedFile by remember { mutableStateOf<FileEntry?>(null) }
    var showFileContent by remember { mutableStateOf(false) }
    var fileContent by remember { mutableStateOf("") }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // 拉取文件后暂存数据，等用户选好保存路径后写入
    var pendingPullData by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    // 系统文件选择器：选好路径后把暂存的文件数据写入
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val (name, data) = pendingPullData ?: return@rememberLauncherForActivityResult
        pendingPullData = null
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            snackbarMessage = "已保存: $name (${formatBytes(data.size.toLong())})"
        } catch (e: Exception) {
            snackbarMessage = "保存失败: ${e.message}"
        }
    }

    LaunchedEffect(Unit) { viewModel.loadFiles() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { snackbarHostState.showSnackbar(it); snackbarMessage = null }
    }

    // 返回拦截：在子目录时返回上级，到根目录才退出
    val isAtRoot = currentPath == "/" || currentPath.isEmpty()
    BackHandler(enabled = !isAtRoot) {
        viewModel.navigateUp()
        selectedFile = null
    }

    // 推送文件：从手机选择文件推送到手表当前目录
    val pushLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val fileName = uri.lastPathSegment ?: "uploaded_file"
                val remotePath = if (currentPath.endsWith("/")) "$currentPath$fileName" else "$currentPath/$fileName"
                snackbarMessage = "正在推送 $fileName..."
                viewModel.pushFile(bytes, remotePath) { result -> snackbarMessage = result }
            }
        } catch (e: Exception) {
            snackbarMessage = "推送失败: ${e.message}"
        }
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val expanded = useDualPane()
    val hPadding = adaptiveHorizontalPadding()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = c.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = hPadding).padding(padding)
        ) {
            // ── Top Bar ──
            Row(modifier = Modifier.fillMaxWidth().padding(top = statusBarPad + 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (!isAtRoot) { viewModel.navigateUp(); selectedFile = null }
                    else onBack()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground) }
                Spacer(Modifier.width(8.dp))
                Text(s.filesTitle, style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
                Spacer(Modifier.weight(1f))
                // 推送按钮
                IconButton(onClick = { pushLauncher.launch("*/*") }) {
                    Icon(Icons.Outlined.Upload, s.filesPushHint, tint = c.accent, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.loadFiles(force = true) }) {
                    Icon(Icons.Outlined.Refresh, s.btnRefresh, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }

            // ── 路径栏 ──
            WearCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.navigateUp(); selectedFile = null }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ArrowUpward, s.filesParent, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(currentPath, style = MaterialTheme.typography.labelMedium, color = c.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }

            // ── 快捷路径 ──
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickPathChip("/sdcard") { viewModel.navigateToPath("/sdcard"); selectedFile = null }
                QuickPathChip("/storage") { viewModel.navigateToPath("/storage"); selectedFile = null }
                QuickPathChip("/system") { viewModel.navigateToPath("/system"); selectedFile = null }
                QuickPathChip("/tmp") { viewModel.navigateToPath("/tmp"); selectedFile = null }
            }

            // ── 文件列表 + 详情 ──
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                }
            } else if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(s.filesEmpty, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                }
            } else if (expanded) {
                // ── 大屏：列表 + 详情分栏 ──
                Row(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = navBarPad + 16.dp, end = 8.dp)
                    ) {
                        itemsIndexed(
                            items = files,
                            key = { _, file -> file.path }
                        ) { _, file ->
                            FileCard(file, selectedFile?.path == file.path,
                                onClick = {
                                    if (file.isDirectory) { viewModel.navigateToPath(file.path); selectedFile = null }
                                    else selectedFile = if (selectedFile?.path == file.path) null else file
                                },
                                onDelete = { viewModel.deleteFile(file.path) { snackbarMessage = it } },
                                onView = { viewModel.readFile(file.path) { content -> fileContent = content; showFileContent = true } },
                                onPull = {
                                    viewModel.pullFile(file.path) { result ->
                                        if (result.success && result.data != null) {
                                            pendingPullData = file.name to result.data
                                            saveLauncher.launch(file.name)
                                        } else {
                                            snackbarMessage = "拉取失败: ${result.message}"
                                        }
                                    }
                                },
                                onInstall = {
                                    viewModel.pullFile(file.path) { result ->
                                        if (result.success && result.data != null) {
                                            if (file.name.endsWith(".apks")) {
                                                val apkParts = unzipApks(result.data)
                                                if (apkParts.isNotEmpty()) {
                                                    viewModel.installSplitApk(apkParts) { msg -> snackbarMessage = msg }
                                                } else {
                                                    snackbarMessage = "⚠️ .apks 文件中未找到 APK"
                                                }
                                            } else {
                                                viewModel.installApk(result.data) { msg -> snackbarMessage = msg }
                                            }
                                        } else {
                                            snackbarMessage = "拉取失败: ${result.message}"
                                        }
                                    }
                                }
                            )
                        }
                    }
                    // 详情面板
                    if (selectedFile != null && !selectedFile!!.isDirectory) {
                        Spacer(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .padding(start = 16.dp, top = 8.dp, bottom = navBarPad + 16.dp)
                        ) {
                            Text(selectedFile!!.name, style = MaterialTheme.typography.titleLarge, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Text(s.filesSize(formatBytes(selectedFile!!.size)), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            Text(s.filesPermission(selectedFile!!.permissions), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                            Text(s.filesModified(selectedFile!!.lastModified), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FileActionButton(s.filesActionView, c.accent, c.onSurface) {
                                    viewModel.readFile(selectedFile!!.path) { content -> fileContent = content; showFileContent = true }
                                }
                                FileActionButton(s.filesActionPull, c.info, c.onSurface) {
                                    viewModel.pullFile(selectedFile!!.path) { result ->
                                        if (result.success && result.data != null) {
                                            pendingPullData = selectedFile!!.name to result.data
                                            saveLauncher.launch(selectedFile!!.name)
                                        } else {
                                            snackbarMessage = "拉取失败: ${result.message}"
                                        }
                                    }
                                }
                                if (selectedFile!!.name.endsWith(".apk") || selectedFile!!.name.endsWith(".apks")) {
                                    FileActionButton(s.filesActionInstall, c.accent, c.onSurface) {
                                        viewModel.pullFile(selectedFile!!.path) { result ->
                                            if (result.success && result.data != null) {
                                                if (selectedFile!!.name.endsWith(".apks")) {
                                                    val apkParts = unzipApks(result.data)
                                                    if (apkParts.isNotEmpty()) {
                                                        viewModel.installSplitApk(apkParts) { msg -> snackbarMessage = msg }
                                                    } else {
                                                        snackbarMessage = "⚠️ .apks 文件中未找到 APK"
                                                    }
                                                } else {
                                                    viewModel.installApk(result.data) { msg -> snackbarMessage = msg }
                                                }
                                            } else {
                                                snackbarMessage = "拉取失败: ${result.message}"
                                            }
                                        }
                                    }
                                }
                                FileActionButton(s.filesActionDelete, c.buttonDanger, c.buttonDangerText) {
                                    viewModel.deleteFile(selectedFile!!.path) { snackbarMessage = it }
                                    selectedFile = null
                                }
                            }
                        }
                    }
                }
            } else {
                // ── 小屏：单栏列表 ──
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = navBarPad + 16.dp)
                ) {
                    itemsIndexed(
                        items = files,
                        key = { _, file -> file.path }
                    ) { _, file ->
                        FileCard(file, selectedFile?.path == file.path,
                            onClick = {
                                if (file.isDirectory) { viewModel.navigateToPath(file.path); selectedFile = null }
                                else selectedFile = if (selectedFile?.path == file.path) null else file
                            },
                            onDelete = { viewModel.deleteFile(file.path) { snackbarMessage = it } },
                            onView = { viewModel.readFile(file.path) { content -> fileContent = content; showFileContent = true } },
                            onPull = {
                                viewModel.pullFile(file.path) { result ->
                                    if (result.success && result.data != null) {
                                        pendingPullData = file.name to result.data
                                        saveLauncher.launch(file.name)
                                    } else {
                                        snackbarMessage = "拉取失败: ${result.message}"
                                    }
                                }
                            },
                            onInstall = {
                                viewModel.pullFile(file.path) { result ->
                                    if (result.success && result.data != null) {
                                        if (file.name.endsWith(".apks")) {
                                            val apkParts = unzipApks(result.data)
                                            if (apkParts.isNotEmpty()) {
                                                viewModel.installSplitApk(apkParts) { msg -> snackbarMessage = msg }
                                            } else {
                                                snackbarMessage = "⚠️ .apks 文件中未找到 APK"
                                            }
                                        } else {
                                            viewModel.installApk(result.data) { msg -> snackbarMessage = msg }
                                        }
                                    } else {
                                        snackbarMessage = "拉取失败: ${result.message}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── 文件内容弹窗 ──
    if (showFileContent) {
        val cr = WearAdbTheme.shape.cornerRadius
        AlertDialog(
            onDismissRequest = { showFileContent = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(cr),
            title = { Text(s.filesContentTitle, style = MaterialTheme.typography.titleMedium, color = c.onSurface) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item {
                        val displayContent = fileContent.take(10000)
                        Text(displayContent, style = MaterialTheme.typography.labelMedium, color = c.onSurface, fontFamily = FontFamily.Monospace)
                        if (fileContent.length > 10000) {
                            Spacer(Modifier.height(8.dp))
                            Text(s.filesContentTooLong(10000, fileContent.length), style = MaterialTheme.typography.labelSmall, color = c.error)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFileContent = false }) { Text(s.btnClose, color = c.accent) } }
        )
    }
}

@Composable
private fun QuickPathChip(path: String, onClick: () -> Unit) {
    val c = WearAdbTheme.colors
    val shape = remember { RoundedCornerShape(16.dp) }
    Box(
        modifier = Modifier.clip(shape).background(c.surfaceVariant, shape)
            .border(1.dp, c.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(path, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
}

@Composable
private fun FileCard(
    file: FileEntry, isSelected: Boolean,
    onClick: () -> Unit, onDelete: () -> Unit, onView: () -> Unit, onPull: () -> Unit, onInstall: () -> Unit
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    val shape = remember { RoundedCornerShape(20.dp) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(if (isSelected) c.selectedBg else c.surfaceVariant, shape)
            .border(1.dp, if (isSelected) c.selectedBorder else c.outlineVariant, shape)
            .clickable(onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (file.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, null,
                tint = if (file.isDirectory) c.folderIcon else c.fileIcon, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    if (!file.isDirectory) { Text(formatBytes(file.size), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant); Spacer(Modifier.width(12.dp)) }
                    Text(file.permissions, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(file.lastModified, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
            }
        }
        AnimatedVisibility(visible = isSelected) {
            Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!file.isDirectory) FileActionButton(s.filesActionView, c.accent, c.onSurface) { onView() }
                if (!file.isDirectory) FileActionButton(s.filesActionPull, c.info, c.onSurface) { onPull() }
                if (!file.isDirectory && (file.name.endsWith(".apk") || file.name.endsWith(".apks"))) {
                    FileActionButton(s.filesActionInstall, c.accent, c.onSurface) { onInstall() }
                }
                FileActionButton(s.filesActionDelete, c.buttonDanger, c.buttonDangerText) { onDelete() }
            }
        }
    }
}

@Composable
private fun FileActionButton(text: String, bgColor: Color, textColor: Color, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(16.dp) }
    Box(
        modifier = Modifier.clip(shape).background(bgColor.copy(alpha = 0.12f), shape)
            .border(1.dp, bgColor.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = textColor) }
}

private fun unzipApks(apksData: ByteArray): List<Pair<String, ByteArray>> {
    val result = mutableListOf<Pair<String, ByteArray>>()
    try {
        val zis = ZipInputStream(apksData.inputStream())
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                result.add(entry.name to zis.readBytes())
            }
            entry = zis.nextEntry
        }
        zis.close()
    } catch (_: Exception) {}
    return result
}


