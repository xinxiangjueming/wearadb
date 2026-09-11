package com.wearadb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.data.repository.ConnectionState
import com.wearadb.data.repository.DiscoveredDevice
import com.wearadb.data.repository.PullResult
import com.wearadb.data.repository.AppInfoResolver
import com.wearadb.data.repository.ApkExtractResult
import com.wearadb.data.repository.apkEntryName
import com.wearadb.data.model.*
import com.wearadb.data.repository.AdbRepository
import com.wearadb.adb.UsbAdbRepository
import com.wearadb.adb.UsbAdbConnectionState
import io.github.muntashirakon.adb.AdbStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for wireless ADB operations + routing through USB ADB.
 * Manages connection, shell, device info, apps, files, and advanced ops.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: AdbRepository,
    private val usbAdbRepository: UsbAdbRepository
) : ViewModel() {

    // ── Wireless ADB connection ──
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val deviceBanner: StateFlow<String> = repository.deviceBanner
    val devices: Flow<List<SavedDevice>> = repository.devices

    // ── USB ADB connection (for routing) ──
    val usbAdbConnectionState: StateFlow<UsbAdbConnectionState> = usbAdbRepository.connectionState

    /** Whether any ADB connection (wireless or wired) is active. */
    val isAnyAdbConnected: StateFlow<Boolean> = combine(
        connectionState, usbAdbConnectionState
    ) { wireless, usb ->
        wireless == ConnectionState.CONNECTED || usb == UsbAdbConnectionState.CONNECTED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // ── Shell ──
    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput.asStateFlow()
    private var shellStream: AdbStream? = null

    // ── Device Info ──
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()
    private val _deviceInfoLoading = MutableStateFlow(false)
    val deviceInfoLoading: StateFlow<Boolean> = _deviceInfoLoading.asStateFlow()

    // ── Apps ──
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()
    private val _appsFilter = MutableStateFlow(AppFilter.ALL)
    val appsFilter: StateFlow<AppFilter> = _appsFilter.asStateFlow()
    private var appsLoadedOnce = false

    // ── 应用名 / 图标（异步补全，不阻塞列表首屏）──
    /** 包名 -> 应用名。列表拿到后立即先用包名渲染，随后被此 map 覆盖。 */
    private val _appLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val appLabels: StateFlow<Map<String, String>> = _appLabels.asStateFlow()

    /** 包名 -> 图标文件。UI 用 File 加载（Coil/自绘），避免把 Bitmap 塞进 StateFlow。 */
    private val _appIcons = MutableStateFlow<Map<String, java.io.File>>(emptyMap())
    val appIcons: StateFlow<Map<String, java.io.File>> = _appIcons.asStateFlow()

    private val _appInfoLoading = MutableStateFlow(false)
    val appInfoLoading: StateFlow<Boolean> = _appInfoLoading.asStateFlow()
    private var appInfoJob: kotlinx.coroutines.Job? = null

    /**
     * 异步解析应用名与图标。
     * 整批下发（app_process 启动开销约 2.8s），每批完成后增量推送到 UI。
     */
    fun loadAppInfo(force: Boolean = false) {
        val pkgList = _apps.value.map { it.packageName }
        if (pkgList.isEmpty()) return
        if (appInfoJob?.isActive == true) {
            android.util.Log.d("VM", "loadAppInfo() 已有任务在跑，跳过")
            return
        }
        appInfoJob = viewModelScope.launch {
            _appInfoLoading.value = true
            try {
                val resolved = when {
                    connectionState.value == ConnectionState.CONNECTED -> {
                        android.util.Log.d("VM", "loadAppInfo() 走无线通道")
                        repository.resolveAppInfo(pkgList, force) { done, total ->
                            android.util.Log.d("VM", "loadAppInfo() 进度 $done/$total")
                        }
                    }
                    isUsbAdbActive -> {
                        android.util.Log.d("VM", "loadAppInfo() 走 USB 通道")
                        repository.resolveAppInfoUsb(pkgList, force) { done, total ->
                            android.util.Log.d("VM", "loadAppInfo() 进度 $done/$total")
                        }
                    }
                    else -> {
                        android.util.Log.d("VM", "loadAppInfo() 无连接，跳过")
                        emptyMap<String, AppInfoResolver.AppInfo>()
                    }
                }
                // 无论新解析还是全命中缓存，都从磁盘/内存缓存补齐 UI
                publishCachedAppInfo(pkgList)
                android.util.Log.d("VM", "loadAppInfo() 完成，新解析 ${resolved.size} 个")
            } catch (e: Exception) {
                android.util.Log.e("VM", "loadAppInfo() 异常: ${e.message}", e)
            } finally {
                _appInfoLoading.value = false
            }
        }
    }

    /** 把磁盘/内存缓存里的名称与图标同步到 UI 状态。 */
    private fun publishCachedAppInfo(packages: List<String>) {
        val labels = HashMap<String, String>()
        val icons = HashMap<String, java.io.File>()
        for (pkg in packages) {
            repository.cachedAppLabel(pkg)?.let { labels[pkg] = it }
            repository.cachedAppIconFile(pkg)?.let { icons[pkg] = it }
        }
        _appLabels.value = labels
        _appIcons.value = icons
    }

    // ── Files ──
    private val _files = MutableStateFlow<List<FileEntry>>(emptyList())
    val files: StateFlow<List<FileEntry>> = _files.asStateFlow()
    private val _currentPath = MutableStateFlow("/sdcard")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    private val _filesLoading = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = _filesLoading.asStateFlow()

    // ── NSD Discovery ──
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = repository.discoveredDevices
    val isDiscovering: StateFlow<Boolean> = repository.isDiscovering

    // ── Pairing ──
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    // ── Screenshot ──
    private val _screenshotData = MutableStateFlow<ByteArray?>(null)
    val screenshotData: StateFlow<ByteArray?> = _screenshotData.asStateFlow()
    private val _screenshotLoading = MutableStateFlow(false)
    val screenshotLoading: StateFlow<Boolean> = _screenshotLoading.asStateFlow()

    // ── Bluetooth dialog ──
    private val _showBluetoothDialog = MutableStateFlow(false)
    val showBluetoothDialog: StateFlow<Boolean> = _showBluetoothDialog.asStateFlow()
    // 追踪是否刚发起过连接请求，用于在 init 中判断 CONNECTED 是否需要弹蓝牙对话框
    private var pendingConnectRequest = false

    // ── Last IP ──
    private val _lastHost = MutableStateFlow("")
    val lastHost: StateFlow<String> = _lastHost.asStateFlow()
    private val _lastPort = MutableStateFlow(55555)
    val lastPort: StateFlow<Int> = _lastPort.asStateFlow()

    // ── Operation result ──
    private val _opResult = MutableSharedFlow<String>()
    val opResult: SharedFlow<String> = _opResult.asSharedFlow()

    init {
        viewModelScope.launch {
            _lastHost.value = repository.getLastHost()
            _lastPort.value = repository.getLastPort()
        }
        // Clear loaded data when connection drops; show bluetooth dialog when connection succeeds
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    _apps.value = emptyList()
                    _files.value = emptyList()
                    _deviceInfo.value = null
                    _currentPath.value = "/sdcard"
                }
                // 连接成功且之前发起过连接请求 → 弹蓝牙对话框（仅限 Wear OS 设备）
                if (state == ConnectionState.CONNECTED && pendingConnectRequest) {
                    pendingConnectRequest = false
                    viewModelScope.launch {
                        val wearOs = repository.isWearOs()
                        android.util.Log.d("VM", "init: CONNECTED after connect request, isWearOs=$wearOs")
                        if (wearOs) {
                            _showBluetoothDialog.value = true
                        }
                    }
                }
            }
        }
    }

    // ── Routing helpers ──
    private val isUsbAdbActive: Boolean
        get() = usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED

    /** 是否存在可用通道（有线优先，与各处的分支顺序一致） */
    private fun anyAdbActive(): Boolean =
        isUsbAdbActive || connectionState.value == ConnectionState.CONNECTED

    /**
     * 通用设备操作的统一派发：两个仓储提供**同名同语义**方法，这里只做通道选择。
     *
     * 以前 USB 侧是 `usbAdbCmd("raw cmd")` 直发命令、无线侧调仓储方法——形状不一致，
     * 一旦有人加新能力只补一边就会静默缺失（本项目多次"点了没反应"都源于此）。
     * 现在两侧都走命名方法，且结果统一落日志，失败不再完全无声。
     */
    private fun deviceOp(usb: suspend () -> Any?, wireless: suspend () -> Any?) {
        viewModelScope.launch {
            try {
                val result = if (isUsbAdbActive) usb() else wireless()
                android.util.Log.d("VM", "deviceOp usb=$isUsbAdbActive result=${result.toString().take(80)}")
            } catch (e: Exception) {
                android.util.Log.e("VM", "deviceOp exception: ${e.message}", e)
            }
        }
    }

    // ── Connection ──
    fun connect(host: String, port: Int = 55555, useTls: Boolean = false) {
        // 防止 UI 快速点击产生并发连接
        val current = connectionState.value
        if (current == ConnectionState.CONNECTING || current == ConnectionState.CONNECTED) {
            android.util.Log.w("VM", "connect() skipped: state=$current")
            return
        }
        android.util.Log.d("VM", "connect() START host=$host port=$port useTls=$useTls current=$current")
        pendingConnectRequest = true
        viewModelScope.launch {
            try {
                repository.connect(host, port, useTls)
                android.util.Log.d("VM", "connect() AFTER repository.connect, state=${repository.connectionState.value}")
            } catch (e: Exception) {
                android.util.Log.e("VM", "connect() EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                pendingConnectRequest = false
            }
        }
    }

    fun confirmDisableBluetooth() {
        _showBluetoothDialog.value = false
        viewModelScope.launch { repository.disableBluetoothAfterConnect() }
    }

    fun dismissBluetoothDialog() { _showBluetoothDialog.value = false }

    fun disconnect() {
        viewModelScope.launch {
            try { shellStream?.close() } catch (_: Exception) {}
            shellStream = null
            repository.disconnect()
        }
    }

    // ── NSD ──
    fun startDiscovery() = repository.startDiscovery()
    fun stopDiscovery() = repository.stopDiscovery()

    fun connectFromDiscovered(device: DiscoveredDevice) {
        android.util.Log.d("VM", "connectFromDiscovered() name=${device.name} host=${device.host} port=${device.port} isPairing=${device.isPairing} -> useTls=${!device.isPairing}")
        connect(device.host, device.port, useTls = !device.isPairing)
    }

    // ── Pairing ──
    fun pair(host: String, port: Int, code: String) {
        android.util.Log.d("VM", "pair() START host=$host port=$port")
        viewModelScope.launch {
            _pairingState.value = PairingState.Pairing
            val result = repository.pair(host, port, code)
            android.util.Log.d("VM", "pair() result: success=${result.success} host=${result.host} port=${result.port} msg=${result.message}")
            if (result.success) {
                _pairingState.value = PairingState.Success(result.message)
                android.util.Log.d("VM", "pair() result.port=${result.port} input port=$port willAutoConnect=${result.port > 0 && result.port != port}")
                if (result.port > 0 && result.port != port) {
                    delay(500)
                    android.util.Log.d("VM", "pair() auto-connecting to ${result.host}:${result.port}")
                    connect(result.host, result.port, useTls = true)
                } else {
                    android.util.Log.w("VM", "pair() NO auto-connect: result.port=${result.port} == input port=$port")
                }
            } else {
                _pairingState.value = PairingState.Error(result.message)
            }
        }
    }

    fun resetPairingState() { _pairingState.value = PairingState.Idle }

    // ── Shell ──
    fun executeCommand(command: String) {
        viewModelScope.launch {
            try {
                if (connectionState.value == ConnectionState.CONNECTED) {
                    _shellOutput.value = withContext(Dispatchers.IO) {
                        try { shellStream?.close() } catch (_: Exception) {}
                        shellStream = null
                        val stream = repository.openShell(command)
                        shellStream = stream
                        val os = stream.openOutputStream()
                        os.write("$command\n".toByteArray())
                        os.flush()
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream.openInputStream()))
                        val sb = StringBuilder()
                        val startTime = System.currentTimeMillis()
                        while (System.currentTimeMillis() - startTime < 15000) {
                            if (reader.ready()) {
                                val line = reader.readLine() ?: break
                                sb.appendLine(line)
                            } else {
                                delay(50)
                            }
                        }
                        sb.toString()
                    }
                } else if (isUsbAdbActive) {
                    _shellOutput.value = "执行中..."
                    _shellOutput.value = usbAdbRepository.executeCommand(command)
                } else {
                    _shellOutput.value = "未连接"
                }
            } catch (e: Exception) {
                _shellOutput.value = "Error: ${e.message}"
            }
        }
    }

    fun executeCommands(commands: List<String>) {
        viewModelScope.launch {
            val results = StringBuilder()
            if (connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val output = withContext(Dispatchers.IO) {
                        val combined = commands.joinToString("; ")
                        val stream = repository.openShell(combined)
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream.openInputStream()))
                        val sb = StringBuilder()
                        val startTime = System.currentTimeMillis()
                        while (System.currentTimeMillis() - startTime < 8000) {
                            if (reader.ready()) {
                                val line = reader.readLine() ?: break
                                sb.appendLine(line)
                            } else {
                                delay(30)
                            }
                        }
                        try { stream.close() } catch (_: Exception) {}
                        sb.toString()
                    }
                    results.appendLine(output.ifEmpty { "(无输出)" })
                } catch (e: Exception) {
                    results.appendLine("Error: ${e.message}")
                }
            } else if (isUsbAdbActive) {
                try {
                    val combined = commands.joinToString("; ")
                    val output = usbAdbRepository.executeCommand(combined)
                    results.appendLine(output.ifEmpty { "(无输出)" })
                } catch (e: Exception) {
                    results.appendLine("Error: ${e.message}")
                }
            } else {
                results.appendLine("未连接")
            }
            _shellOutput.value = results.toString()
        }
    }

    // ── Device Info ──
    fun loadDeviceInfo(force: Boolean = false) {
        android.util.Log.d("VM", "loadDeviceInfo() force=$force wirelessState=${connectionState.value} usbState=${usbAdbConnectionState.value} existingInfo=${_deviceInfo.value != null}")
        viewModelScope.launch {
            if (!force && _deviceInfo.value != null) {
                android.util.Log.d("VM", "loadDeviceInfo() SKIPPED: already have data and not forced")
                return@launch
            }
            _deviceInfoLoading.value = true
            try {
                if (connectionState.value == ConnectionState.CONNECTED) {
                    android.util.Log.d("VM", "loadDeviceInfo() calling repository.getDeviceInfo()")
                    _deviceInfo.value = repository.getDeviceInfo()
                    android.util.Log.d("VM", "loadDeviceInfo() got info: model=${_deviceInfo.value?.model}")
                } else if (isUsbAdbActive) {
                    android.util.Log.d("VM", "loadDeviceInfo() calling usbAdbRepository.getDeviceInfo()")
                    _deviceInfo.value = usbAdbRepository.getDeviceInfo()
                } else {
                    android.util.Log.w("VM", "loadDeviceInfo() NO connection active, cannot load")
                }
            } catch (e: Exception) {
                android.util.Log.e("VM", "loadDeviceInfo() EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            }
            _deviceInfoLoading.value = false
        }
    }

    // ── Apps ──
    fun loadApps(force: Boolean = false) {
        android.util.Log.d("VM", "loadApps() force=$force appsLoadedOnce=$appsLoadedOnce existingCount=${_apps.value.size}")
        viewModelScope.launch {
            if (!force && appsLoadedOnce && _apps.value.isNotEmpty()) {
                android.util.Log.d("VM", "loadApps() SKIPPED: already loaded ${_apps.value.size} apps")
                return@launch
            }
            _appsLoading.value = true
            try {
                _apps.value = if (connectionState.value == ConnectionState.CONNECTED) {
                    repository.rememberAppInfoSerial()
                    repository.getInstalledPackages()
                } else if (isUsbAdbActive) {
                    usbAdbRepository.getInstalledPackages()
                } else {
                    emptyList()
                }
                appsLoadedOnce = true
                val enabledCount = _apps.value.count { it.isEnabled }
                val disabledCount = _apps.value.count { !it.isEnabled }
                android.util.Log.d("VM", "loadApps() loaded ${_apps.value.size} apps, enabled=$enabledCount, disabled=$disabledCount")
                // 名称与图标异步补全：列表先用包名快速出图，随后被真实名称/图标覆盖
                if (_apps.value.isNotEmpty()) loadAppInfo(force = force)
            } catch (e: Exception) {
                android.util.Log.e("VM", "loadApps() EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            }
            _appsLoading.value = false
        }
    }

    fun setAppsFilter(filter: AppFilter) { _appsFilter.value = filter }

    /**
     * 应用操作的统一入口：按"当前生效通道"路由到有线 / 无线仓储。
     *
     * 历史缺陷：uninstall/clearData/forceStop/disable/enable 只调无线 repository，
     * 有线会话下无线 manager 未连接 → runSingleCommand 吞掉异常返回空串，
     * 命令根本没发出去，UI 表现为"点了没反应 + 空白 toast"。
     * 两个通道都不可用时直接回传空串，由 UI 层给出"未连接/无响应"提示。
     */
    private fun appOp(
        tag: String,
        refreshApps: Boolean = false,
        usb: suspend () -> String,
        wireless: suspend () -> String,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (!anyAdbActive()) {
                android.util.Log.w("VM", "appOp($tag) skipped: no active adb channel")
                onResult("")
                return@launch
            }
            val result = try {
                if (isUsbAdbActive) usb() else wireless()
            } catch (e: Exception) {
                android.util.Log.e("VM", "appOp($tag) exception: ${e.message}", e)
                "执行失败: ${e.message}"
            }
            android.util.Log.d("VM", "appOp($tag) usb=$isUsbAdbActive result=${result.take(120)}")
            onResult(result.trim())
            if (refreshApps) loadApps(force = true)
        }
    }

    fun uninstallApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "uninstallApp",
        refreshApps = true,
        usb = { usbAdbRepository.uninstallApp(pkg) },
        wireless = { repository.uninstallApp(pkg) },
        onResult = onResult
    )

    /** 卸载但保留数据（pm uninstall -k），卸载后同样需要刷新列表 */
    fun uninstallAppKeepData(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "uninstallAppKeepData",
        refreshApps = true,
        usb = { usbAdbRepository.uninstallAppKeepData(pkg) },
        wireless = { repository.uninstallAppKeepData(pkg) },
        onResult = onResult
    )

    fun clearAppData(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "clearAppData",
        usb = { usbAdbRepository.clearAppData(pkg) },
        wireless = { repository.clearAppData(pkg) },
        onResult = onResult
    )

    fun forceStopApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "forceStopApp",
        usb = { usbAdbRepository.forceStopApp(pkg) },
        wireless = { repository.forceStopApp(pkg) },
        onResult = onResult
    )

    fun disableApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "disableApp",
        refreshApps = true,
        usb = { usbAdbRepository.disableApp(pkg) },
        wireless = { repository.disableApp(pkg) },
        onResult = onResult
    )

    fun enableApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "enableApp",
        refreshApps = true,
        usb = { usbAdbRepository.enableApp(pkg) },
        wireless = { repository.enableApp(pkg) },
        onResult = onResult
    )

    /**
     * 导出安装包到下载目录（`Download/WearAdb`），返回结构化结果供 UI 生成当前语言文案。
     *
     * 全链路**流式**：`pm path` 取路径 → SYNC 数据块直接写目标文件 / zip 条目，中间不经过任何
     * ByteArray 缓冲。此前把整份 APK 读进内存的方案，在 250MB 级应用上会
     * `OutOfMemoryError`（真机崩溃已复现：ByteArrayOutputStream 扩容单次申请 256MB）。
     * 单个 APK 存为 `<包名>.apk`；含 split 时打包成 `<包名>.apks`（本应用安装流程可直接读）。
     *
     * @param onProgress 进度回调 (已写字节, 总字节)。总字节为 -1 表示设备未给出大小（UI 退化显示已传输量）。
     *                   首次立即回调 (0, total)，之后按 [PROGRESS_STEP] 粒度节流，结束再补一次终值。
     */
    suspend fun exportApk(
        pkg: String,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): ApkExtractResult = withContext(Dispatchers.IO) {
        try {
            val paths = if (isUsbAdbActive) {
                usbAdbRepository.packageApkPaths(pkg)
            } else {
                repository.packageApkPaths(pkg)
            }
            if (paths.isEmpty()) return@withContext ApkExtractResult.NoApkPath

            // 总大小用于百分比：逐个 stat，任一取不到就退化为"未知总量"
            val sizes = paths.map { remoteFileSize(it) }
            val total = if (sizes.all { it > 0L }) sizes.sum() else -1L
            var writtenTotal = 0L
            var lastReported = 0L
            onProgress?.invoke(0L, total)

            val (dir, isPublic) = resolveExportDir()
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext ApkExtractResult.Failure("无法创建目录: ${dir.absolutePath}")
            }
            val fileName = if (paths.size == 1) "$pkg.apk" else "$pkg.apks"
            val dest = java.io.File(dir, fileName)

            // 用计数流包一层 sink：pullTo 无需改签名，进度按 PROGRESS_STEP 粒度上报
            val pull: suspend (String, java.io.OutputStream) -> Boolean = { remote, sink ->
                val counting = object : java.io.FilterOutputStream(sink) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        super.write(b, off, len)
                        writtenTotal += len
                        if (writtenTotal - lastReported >= PROGRESS_STEP) {
                            lastReported = writtenTotal
                            onProgress?.invoke(writtenTotal, total)
                        }
                    }

                    override fun write(b: Int) {
                        super.write(b)
                        writtenTotal += 1
                    }
                }
                if (isUsbAdbActive) {
                    usbAdbRepository.pullTo(remote, counting)
                } else {
                    repository.pullTo(remote, counting)
                }
            }

            var failedRemote: String? = null
            if (paths.size == 1) {
                dest.outputStream().buffered().use { out ->
                    if (!pull(paths[0], out)) failedRemote = paths[0]
                }
            } else {
                // Split APK：打包成 .apks，逐个 split 流式写进 zip 条目（不落中间文件）
                java.util.zip.ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                    for ((index, remote) in paths.withIndex()) {
                        zip.putNextEntry(java.util.zip.ZipEntry(apkEntryName(remote, index)))
                        val ok = pull(remote, zip)
                        zip.closeEntry()
                        if (!ok) { failedRemote = remote; break }
                    }
                }
            }

            if (failedRemote != null || !dest.exists() || dest.length() == 0L) {
                val reason = failedRemote?.let { "拉取失败: $it" } ?: "写入为空"
                runCatching { dest.delete() }
                return@withContext ApkExtractResult.Failure(reason)
            }
            // 收尾补一次终值：总量未知时用实际字节数充当 100%，避免进度条停在半途
            onProgress?.invoke(writtenTotal, if (total > 0L) total else writtenTotal)
            android.util.Log.d("VM", "exportApk ok: $fileName (${dest.length()} bytes, ${paths.size} apk)")
            ApkExtractResult.Success(
                fileName = fileName,
                location = if (isPublic) "Download/WearAdb" else dir.absolutePath,
                fileCount = paths.size
            )
        } catch (e: Exception) {
            android.util.Log.e("VM", "exportApk exception: ${e.message}", e)
            ApkExtractResult.Failure(e.message ?: "unknown error")
        }
    }

    /** 进度上报粒度：1MB。太密会频繁触发 UI 重组，太疏进度条会一跳一跳 */
    private val PROGRESS_STEP = 1024L * 1024L

    private suspend fun remoteFileSize(path: String): Long =
        if (isUsbAdbActive) usbAdbRepository.remoteFileSize(path) else repository.remoteFileSize(path)

    /**
     * 导出目录：默认公共下载目录 `Download/WearAdb`（应用已申请 MANAGE_EXTERNAL_STORAGE）；
     * 未授予「所有文件访问」时回退到 App 专属外部目录，避免静默写入失败。
     */
    private fun resolveExportDir(): Pair<java.io.File, Boolean> {
        val publicBase = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            } else null
        } else {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val base = publicBase ?: appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return java.io.File(base, "WearAdb") to (publicBase != null)
    }

    fun installApk(apkData: ByteArray, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("正在安装...")
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.installApk(apkData)
            } else {
                repository.installApk(apkData)
            }
            onResult(result)
            loadApps(force = true)
        }
    }

    fun installSplitApk(apkFiles: List<Pair<String, ByteArray>>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("正在安装 Split APK (${apkFiles.size} 个文件)...")
            val result = if (isUsbAdbActive) {
                usbAdbRepository.installSplitApk(apkFiles)
            } else {
                repository.installSplitApk(apkFiles)
            }
            onResult(result)
            loadApps(force = true)
        }
    }

    fun installApkFile(apkFile: java.io.File, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("正在安装...")
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.installApk(apkFile)
            } else {
                repository.installApk(apkFile)
            }
            onResult(result)
            loadApps(force = true)
        }
    }

    /** 同步版本：等待安装完成后才返回，用于临时文件需要在安装期间保持存在的场景 */
    suspend fun installApkFileSync(apkFile: java.io.File, onResult: (String) -> Unit) {
        onResult("正在安装...")
        val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
            usbAdbRepository.installApk(apkFile)
        } else {
            repository.installApk(apkFile)
        }
        onResult(result)
        loadApps(force = true)
    }

    fun installSplitApkFiles(apkFiles: List<Pair<String, java.io.File>>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("正在安装 Split APK (${apkFiles.size} 个文件)...")
            val result = if (isUsbAdbActive) {
                usbAdbRepository.installSplitApkFiles(apkFiles)
            } else {
                repository.installSplitApkFiles(apkFiles)
            }
            onResult(result)
            loadApps(force = true)
        }
    }

    suspend fun installSplitApkFromApks(apksFile: java.io.File, onStatus: (String) -> Unit): String? {
        return try {
            onStatus("正在解析 .apks...")
            val result = if (isUsbAdbActive) {
                usbAdbRepository.installSplitApkFromApksFile(apksFile)
            } else {
                repository.installSplitApkFromApksFile(apksFile)
            }
            onStatus(result)
            loadApps(force = true)
            result
        } catch (e: Exception) {
            val msg = "安装异常: ${e.message}"
            onStatus(msg)
            msg
        }
    }

    // ── Files ──
    fun loadFiles(path: String = _currentPath.value, force: Boolean = false) {
        viewModelScope.launch {
            if (!force && _files.value.isNotEmpty() && path == _currentPath.value) return@launch
            _filesLoading.value = true; _currentPath.value = path
            try {
                _files.value = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                    usbAdbRepository.listFiles(path)
                } else {
                    repository.listFiles(path)
                }
            } catch (_: Exception) { _files.value = emptyList() }
            _filesLoading.value = false
        }
    }

    fun navigateToPath(path: String) = loadFiles(path)

    fun navigateUp() {
        val parent = _currentPath.value.substringBeforeLast("/", "/")
        loadFiles(parent)
    }

    fun deleteFile(path: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.deleteFile(path)
            } else {
                repository.deleteFile(path)
            }
            onResult(result.trim()); loadFiles(force = true)
        }
    }

    fun createDirectory(path: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.createDirectory(path)
            } else {
                repository.createDirectory(path)
            }
            onResult(result.trim()); loadFiles(force = true)
        }
    }

    fun readFile(path: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.readFile(path)
            } else {
                repository.readFile(path)
            }
            onResult(result)
        }
    }

    fun pushFile(data: ByteArray, remotePath: String, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            // 两侧同名方法：USB 版的临时文件处理已下沉到 UsbAdbRepository.pushFile(ByteArray)
            val result = if (isUsbAdbActive) {
                usbAdbRepository.pushFile(data, remotePath)
            } else {
                repository.pushFile(data, remotePath)
            }
            _opResult.emit(result)
            onResult?.invoke(result)
            loadFiles(force = true)
        }
    }

    fun pullFile(remotePath: String, onResult: (PullResult) -> Unit) {
        viewModelScope.launch {
            // 两侧同名方法且返回同一类型 PullResult
            onResult(
                if (isUsbAdbActive) usbAdbRepository.pullFile(remotePath)
                else repository.pullFile(remotePath)
            )
        }
    }

    // ── Advanced Ops ──
    fun reboot(mode: String = "") {
        if (isUsbAdbActive) {
            viewModelScope.launch {
                // 两侧同名方法，不再直发裸命令
                when (mode) {
                    "recovery" -> usbAdbRepository.rebootRecovery()
                    "bootloader" -> usbAdbRepository.rebootBootloader()
                    "shutdown" -> usbAdbRepository.shutdown()
                    else -> usbAdbRepository.reboot()
                }
                if (mode == "bootloader" || mode == "recovery" || mode == "shutdown") {
                    usbAdbRepository.disconnect()
                }
            }
        } else {
            viewModelScope.launch {
                when (mode) {
                    "recovery" -> { repository.rebootRecovery(); disconnect() }
                    "bootloader" -> { repository.rebootBootloader(); disconnect() }
                    "shutdown" -> { repository.shutdown(); disconnect() }
                    else -> repository.reboot()
                }
            }
        }
    }

    fun takeScreenshot() {
        viewModelScope.launch {
            _screenshotLoading.value = true
            _screenshotData.value = if (isUsbAdbActive) {
                usbAdbRepository.screenshot()
            } else {
                repository.screenshot()
            }
            _screenshotLoading.value = false
        }
    }

    fun clearScreenshot() { _screenshotData.value = null }

    // ── 屏幕查看（B1: scrcpy-server，USB / 无线通道共用 ScreenMirrorEngine） ──

    /** 按当前通道选择 engine + transport（USB 连接时优先 USB 通道） */
    private fun currentMirror(): Pair<com.wearadb.adb.ScreenMirrorEngine, com.wearadb.adb.MirrorTransport> =
        if (isUsbAdbActive) usbAdbRepository.mirrorEngine to usbAdbRepository.mirrorTransport()
        else repository.mirrorEngine to repository.mirrorTransport()

    val mirrorStatus: StateFlow<com.wearadb.adb.MirrorStatus> = combine(
        usbAdbRepository.mirrorStatus,
        repository.mirrorStatus,
        usbAdbRepository.connectionState
    ) { usb, wl, usbState ->
        if (usbState == UsbAdbConnectionState.CONNECTED) usb else wl
    }.stateIn(viewModelScope, SharingStarted.Eagerly, com.wearadb.adb.MirrorStatus.Idle)

    val mirrorVideoSize: StateFlow<Pair<Int, Int>?> = combine(
        usbAdbRepository.mirrorVideoSize,
        repository.mirrorVideoSize,
        usbAdbRepository.connectionState
    ) { usb, wl, usbState ->
        if (usbState == UsbAdbConnectionState.CONNECTED) usb else wl
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mirrorRealSize: StateFlow<Pair<Int, Int>?> = combine(
        usbAdbRepository.mirrorRealSize,
        repository.mirrorRealSize,
        usbAdbRepository.connectionState
    ) { usb, wl, usbState ->
        if (usbState == UsbAdbConnectionState.CONNECTED) usb else wl
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 画质/帧率选项：maxSize=0 不限制（scrcpy max_size，8 的倍数才有意义）；bitRate 单位 bps；maxFps=0 不限制
    private val _mirrorMaxSize = MutableStateFlow(0)
    val mirrorMaxSize: StateFlow<Int> = _mirrorMaxSize.asStateFlow()

    private val _mirrorBitRate = MutableStateFlow(4_000_000)
    val mirrorBitRate: StateFlow<Int> = _mirrorBitRate.asStateFlow()

    private val _mirrorMaxFps = MutableStateFlow(0f)
    val mirrorMaxFps: StateFlow<Float> = _mirrorMaxFps.asStateFlow()

    // 会话开关：只读（纯 UI 门控，不注入）；熄屏（SET_SCREEN_POWER_MODE）；保持唤醒（stay_awake）。声音恒不转发。
    private val _mirrorReadOnly = MutableStateFlow(false)
    val mirrorReadOnly: StateFlow<Boolean> = _mirrorReadOnly.asStateFlow()

    private val _mirrorTurnOffScreen = MutableStateFlow(false)
    val mirrorTurnOffScreen: StateFlow<Boolean> = _mirrorTurnOffScreen.asStateFlow()

    private val _mirrorStayAwake = MutableStateFlow(false)
    val mirrorStayAwake: StateFlow<Boolean> = _mirrorStayAwake.asStateFlow()

    /** 投屏页当前 Surface（运行中变更选项 → 无缝重启会话需要） */
    @Volatile
    private var mirrorSurface: android.view.Surface? = null

    private fun buildMirrorOptions() = com.wearadb.adb.MirrorOptions(
        maxSize = _mirrorMaxSize.value,
        bitRate = _mirrorBitRate.value,
        maxFps = _mirrorMaxFps.value,
        turnOffScreen = _mirrorTurnOffScreen.value,
        stayAwake = _mirrorStayAwake.value,
    )

    /**
     * 启动屏幕查看。surface 来自投屏页 SurfaceView（surfaceCreated 回调）。
     * 通道未连接时 engine 内部命令失败会置 Error，UI 据此提示。
     */
    fun startMirror(surface: android.view.Surface) {
        mirrorSurface = surface
        viewModelScope.launch(Dispatchers.IO) {
            val (engine, transport) = currentMirror()
            engine.startSafe(transport, surface, buildMirrorOptions())
        }
    }

    fun stopMirror() {
        currentMirror().first.stop()
    }

    /** 变更画质/开关选项；正在投屏则用新参数无缝重启（只读除外，纯 UI 门控），空闲/错误态仅保存待下次启动生效。 */
    fun setMirrorMaxSize(v: Int) { _mirrorMaxSize.value = v; restartMirrorIfRunning() }

    fun setMirrorBitRate(v: Int) { _mirrorBitRate.value = v; restartMirrorIfRunning() }

    fun setMirrorMaxFps(v: Float) { _mirrorMaxFps.value = v; restartMirrorIfRunning() }

    fun setMirrorReadOnly(v: Boolean) { _mirrorReadOnly.value = v }

    fun setMirrorTurnOffScreen(v: Boolean) { _mirrorTurnOffScreen.value = v; restartMirrorIfRunning() }

    fun setMirrorStayAwake(v: Boolean) { _mirrorStayAwake.value = v; restartMirrorIfRunning() }

    private fun restartMirrorIfRunning() {
        val sf = mirrorSurface ?: return
        val st = usbAdbRepository.mirrorStatus.value
        if (st !is com.wearadb.adb.MirrorStatus.Streaming &&
            st !is com.wearadb.adb.MirrorStatus.Starting
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            val (engine, transport) = currentMirror()
            engine.startSafe(transport, sf, buildMirrorOptions())
        }
    }

    fun tap(x: Int, y: Int) =
        deviceOp({ usbAdbRepository.tap(x, y) }, { repository.tap(x, y) })

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300) =
        deviceOp({ usbAdbRepository.swipe(x1, y1, x2, y2, dur) }, { repository.swipe(x1, y1, x2, y2, dur) })

    fun keyEvent(code: Int) =
        deviceOp({ usbAdbRepository.keyEvent(code) }, { repository.keyEvent(code) })

    fun enableWifi() = deviceOp({ usbAdbRepository.enableWifi() }, { repository.enableWifi() })
    fun disableWifi() = deviceOp({ usbAdbRepository.disableWifi() }, { repository.disableWifi() })
    fun enableBluetooth() = deviceOp({ usbAdbRepository.enableBluetooth() }, { repository.enableBluetooth() })
    fun disableBluetooth() = deviceOp({ usbAdbRepository.disableBluetooth() }, { repository.disableBluetooth() })
    fun volumeUp() = deviceOp({ usbAdbRepository.volumeUp() }, { repository.volumeUp() })
    fun volumeDown() = deviceOp({ usbAdbRepository.volumeDown() }, { repository.volumeDown() })
    fun volumeMute() = deviceOp({ usbAdbRepository.volumeMute() }, { repository.volumeMute() })
    fun screenOn() = deviceOp({ usbAdbRepository.screenOn() }, { repository.screenOn() })
    fun screenOff() = deviceOp({ usbAdbRepository.screenOff() }, { repository.screenOff() })
    fun inputText(text: String) =
        deviceOp({ usbAdbRepository.inputText(text) }, { repository.inputText(text) })

    fun removeDevice(address: String) { viewModelScope.launch { repository.removeDevice(address) } }
    fun toggleFavorite(address: String) { viewModelScope.launch { repository.toggleFavorite(address) } }

    override fun onCleared() {
        super.onCleared()
        try { shellStream?.close() } catch (_: Exception) {}
        shellStream = null
    }
}

enum class AppFilter { ALL, SYSTEM, THIRD_PARTY, DISABLED }

sealed class PairingState {
    data object Idle : PairingState()
    data object Pairing : PairingState()
    data class Success(val message: String) : PairingState()
    data class Error(val message: String) : PairingState()
}
