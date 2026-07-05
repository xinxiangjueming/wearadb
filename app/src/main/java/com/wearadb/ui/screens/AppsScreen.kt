package com.wearadb.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.wearadb.data.model.AppEntry
import com.wearadb.ui.AppFilter
import com.wearadb.ui.ConnectionViewModel
import com.wearadb.ui.LocalStrings
import com.wearadb.ui.components.*
import com.wearadb.ui.theme.WearAdbTheme
import com.wearadb.ui.utils.adaptiveHorizontalPadding

@Composable
fun AppsScreen(
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    val apps by viewModel.apps.collectAsState()
    val loading by viewModel.appsLoading.collectAsState()
    val filter by viewModel.appsFilter.collectAsState()

    // recomposition tracking
    var recompositionCount = remember { 0 }
    SideEffect {
        recompositionCount++
        if (recompositionCount % 5 == 0 || recompositionCount <= 2) {
            android.util.Log.d("AppsScreen", "recomposition #$recompositionCount, apps=${apps.size}")
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var expandedPkg by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadApps() }

    val filteredApps = remember(apps, filter, searchQuery) {
        val base = when (filter) {
            AppFilter.ALL -> apps
            AppFilter.SYSTEM -> apps.filter { it.isSystem }
            AppFilter.THIRD_PARTY -> apps.filter { !it.isSystem }
        }
        if (searchQuery.isBlank()) base else base.filter { it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    // 分组数据（仅"全部"模式使用）
    val systemApps = remember(filteredApps) { filteredApps.filter { it.isSystem } }
    val thirdPartyApps = remember(filteredApps) { filteredApps.filter { !it.isSystem } }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { snackbarHostState.showSnackbar(it); snackbarMessage = null }
    }

    val statusBarPad = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // APK 安装：拉起系统文件选择器（支持 .apk 和 .apks）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickerActive by remember { mutableStateOf(false) }

    // 超时保护：如果 15 秒内回调未触发（MIUI 系统 NPE 导致），自动复位
    LaunchedEffect(pickerActive) {
        if (pickerActive) {
            kotlinx.coroutines.delay(15000)
            if (pickerActive) {
                pickerActive = false
                snackbarMessage = s.appsPickerTimeout
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        pickerActive = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = uri.lastPathSegment ?: ""
                if (fileName.endsWith(".apks", ignoreCase = true) ||
                    fileName.endsWith(".xapk", ignoreCase = true) ||
                    fileName.endsWith(".apkm", ignoreCase = true)
                ) {
                    // ── Split APK: 流式解压 + 逐个推送（避免 OOM）──
                    // 写入临时文件，保持 FD 打开防止 MIUI 清理
                    val tmpApks = File(context.cacheDir, "wearadb_split.apks")
                    try {
                        // 1. 从 URI 拷贝到临时文件
                        var copied = -1L
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            java.io.FileOutputStream(tmpApks).use { output ->
                                copied = input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        android.util.Log.d("AppsScreen", "apks copyTo: $copied bytes, file size=${tmpApks.length()}")
                        if (copied <= 0L || !tmpApks.exists() || tmpApks.length() == 0L) {
                            launch(Dispatchers.Main) { snackbarMessage = "读取 .apks 失败" }
                            return@launch
                        }

                        // 2. 流式解压并推送每个 APK（不把全部 APK 同时加载到内存）
                        val result = viewModel.installSplitApkFromApks(tmpApks) { msg ->
                            snackbarMessage = msg
                        }
                        if (result != null) {
                            snackbarMessage = result
                        }
                    } finally {
                        try { tmpApks.delete() } catch (_: Exception) {}
                    }
                } else {
                    // ── 普通 APK: 拷贝到临时文件后流式推送（避免 OOM）──
                    val tmpApk = File(context.cacheDir, "wearadb_install.apk")
                    try {
                        var copied = -1L
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            java.io.FileOutputStream(tmpApk).use { output ->
                                copied = input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        android.util.Log.d("AppsScreen", "apk copyTo: $copied bytes, file size=${tmpApk.length()}")
                        if (copied <= 0L || !tmpApk.exists() || tmpApk.length() == 0L) {
                            launch(Dispatchers.Main) { snackbarMessage = "读取 APK 失败" }
                            return@launch
                        }
                        // 同步执行安装，确保临时文件在安装完成前不被删除
                        viewModel.installApkFileSync(tmpApk) { result ->
                            snackbarMessage = result
                        }
                    } finally {
                        try { tmpApk.delete() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppsScreen", "install error: ${e.message}", e)
                launch(Dispatchers.Main) { snackbarMessage = "安装异常: ${e.message}" }
            }
        }
    }

    val hPadding = adaptiveHorizontalPadding()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = c.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(280.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = hPadding).padding(padding),
            contentPadding = PaddingValues(top = statusBarPad + 8.dp, bottom = navBarPad + 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, s.btnBack, tint = c.onBackground) }
                    Spacer(Modifier.width(8.dp))
                    Text(s.appsTitle, style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
                    Spacer(Modifier.weight(1f))
                    Text("${filteredApps.size}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { pickerActive = true; apkPicker.launch("*/*") }) {
                        Icon(Icons.Outlined.InstallMobile, s.appsInstallApk, tint = c.accent, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.loadApps(force = true) }) {
                        Icon(Icons.Outlined.Refresh, s.btnRefresh, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { WearInput(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = s.appsSearchHint) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipItem(s.appsFilterAll, filter == AppFilter.ALL) { viewModel.setAppsFilter(AppFilter.ALL) }
                    FilterChipItem(s.appsFilterSystem, filter == AppFilter.SYSTEM) { viewModel.setAppsFilter(AppFilter.SYSTEM) }
                    FilterChipItem(s.appsFilterThird, filter == AppFilter.THIRD_PARTY) { viewModel.setAppsFilter(AppFilter.THIRD_PARTY) }
                }
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                    }
                }
            }
            if (filter == AppFilter.ALL && searchQuery.isBlank()) {
                if (systemApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s.appsSystemCount(systemApps.size)) }
                    items(systemApps, key = { it.packageName }) { app ->
                        AppListItem(app, expandedPkg, onToggleExpand = { expandedPkg = it },
                            onUninstall = { viewModel.uninstallApp(app.packageName) { snackbarMessage = it } },
                            onClearData = { viewModel.clearAppData(app.packageName) { snackbarMessage = it } },
                            onForceStop = { viewModel.forceStopApp(app.packageName) { snackbarMessage = it } },
                            onToggleEnabled = {
                                if (app.isEnabled) viewModel.disableApp(app.packageName) { snackbarMessage = it }
                                else viewModel.enableApp(app.packageName) { snackbarMessage = it }
                                viewModel.loadApps(force = true)
                            })
                    }
                }
                if (thirdPartyApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s.appsThirdCount(thirdPartyApps.size)) }
                    items(thirdPartyApps, key = { it.packageName }) { app ->
                        AppListItem(app, expandedPkg, onToggleExpand = { expandedPkg = it },
                            onUninstall = { viewModel.uninstallApp(app.packageName) { snackbarMessage = it } },
                            onClearData = { viewModel.clearAppData(app.packageName) { snackbarMessage = it } },
                            onForceStop = { viewModel.forceStopApp(app.packageName) { snackbarMessage = it } },
                            onToggleEnabled = {
                                if (app.isEnabled) viewModel.disableApp(app.packageName) { snackbarMessage = it }
                                else viewModel.enableApp(app.packageName) { snackbarMessage = it }
                                viewModel.loadApps(force = true)
                            })
                    }
                }
            } else {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppListItem(app, expandedPkg, onToggleExpand = { expandedPkg = it },
                        onUninstall = { viewModel.uninstallApp(app.packageName) { snackbarMessage = it } },
                        onClearData = { viewModel.clearAppData(app.packageName) { snackbarMessage = it } },
                        onForceStop = { viewModel.forceStopApp(app.packageName) { snackbarMessage = it } },
                        onToggleEnabled = {
                            if (app.isEnabled) viewModel.disableApp(app.packageName) { snackbarMessage = it }
                            else viewModel.enableApp(app.packageName) { snackbarMessage = it }
                            viewModel.loadApps(force = true)
                        })
                }
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: AppEntry,
    expandedPkg: String?,
    onToggleExpand: (String?) -> Unit,
    onUninstall: () -> Unit,
    onClearData: () -> Unit,
    onForceStop: () -> Unit,
    onToggleEnabled: () -> Unit
) {
    val isExpanded = expandedPkg == app.packageName
    AppCard(
        app = app, expanded = isExpanded,
        onToggleExpand = { onToggleExpand(if (isExpanded) null else app.packageName) },
        onUninstall = onUninstall,
        onClearData = onClearData,
        onForceStop = onForceStop,
        onToggleEnabled = onToggleEnabled
    )
}

@Composable
private fun FilterChipItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = WearAdbTheme.colors
    val cr = WearAdbTheme.shape.cornerRadius
    val shape = remember { RoundedCornerShape(cr) }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) c.chipSelected else c.chipDefault, shape)
            .then(if (selected) Modifier.border(1.dp, c.selectedBorder, shape) else Modifier.border(1.dp, c.outlineVariant, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) c.chipSelectedText else c.chipDefaultText)
    }
}

@Composable
private fun AppCard(
    app: AppEntry, expanded: Boolean, onToggleExpand: () -> Unit,
    onUninstall: () -> Unit, onClearData: () -> Unit, onForceStop: () -> Unit, onToggleEnabled: () -> Unit
) {
    val c = WearAdbTheme.colors
    val s = LocalStrings.current
    val cr = WearAdbTheme.shape.cornerRadius
    val shape = remember { RoundedCornerShape(cr) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(c.surfaceVariant, shape)
            .border(1.dp, c.outlineVariant, shape).clickable(onClick = onToggleExpand).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (app.isSystem) c.systemAppDot else c.thirdPartyAppDot))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.packageName, style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    if (app.versionName.isNotEmpty()) Text("v${app.versionName}", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    if (!app.isEnabled) { Spacer(Modifier.width(8.dp)); Text(s.appsDisabled, style = MaterialTheme.typography.labelSmall, color = c.disabledBadge) }
                }
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (expanded) {
            Column {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = c.outlineVariant); Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppActionButton(s.appsActionStop, c.buttonSecondary, c.buttonSecondaryText) { onForceStop() }
                    AppActionButton(s.appsActionClear, c.info, c.onSurface) { onClearData() }
                    AppActionButton(if (app.isEnabled) s.appsActionDisable else s.appsActionEnable, c.buttonSecondary, c.buttonSecondaryText) { onToggleEnabled() }
                }
                Spacer(Modifier.height(8.dp))
                if (!app.isSystem) AppActionButton(s.appsActionUninstall, c.buttonDanger, c.buttonDangerText) { onUninstall() }
            }
        }
    }
}

@Composable
private fun AppActionButton(text: String, bgColor: Color, textColor: Color, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(20.dp) }
    Box(
        modifier = Modifier.clip(shape).background(bgColor.copy(alpha = 0.12f), shape)
            .border(1.dp, bgColor.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = textColor) }
}
