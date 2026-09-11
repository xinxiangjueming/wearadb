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
import com.wearadb.R
import com.wearadb.util.OperationNotifier
import io.github.muntashirakon.adb.AdbStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 设备侧操作的状态栏通知封装（提取/安装/冻结/解冻/卸载等） */
    private val notifier = OperationNotifier(appContext)

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
     *
     * 通知：每个操作发出「进行中 → 完成/失败」两条状态栏通知，文案走 [R.string] 多语言。
     *
     * @param pkg 包名（用于通知去重 id 与展示应用名）
     * @param opLabelRes 操作名资源（冻结/卸载/…），用于通知标题
     */
    private fun appOp(
        tag: String,
        pkg: String,
        opLabelRes: Int,
        refreshApps: Boolean = false,
        usb: suspend () -> String,
        wireless: suspend () -> String,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val id = OperationNotifier.idFor("$pkg:$tag")
            val label = appContext.getString(opLabelRes)
            val name = appLabels.value[pkg]?.takeIf { it.isNotBlank() } ?: pkg
            if (!anyAdbActive()) {
                notifier.complete(
                    id, appContext.getString(R.string.notif_op_failed, label),
                    "$name：${appContext.getString(R.string.apps_not_connected)}"
                )
                android.util.Log.w("VM", "appOp($tag) skipped: no active adb channel")
                onResult("")
                return@launch
            }
            notifier.start(id, appContext.getString(R.string.notif_op_in_progress, label), name)
            val result = try {
                if (isUsbAdbActive) usb() else wireless()
            } catch (e: Exception) {
                android.util.Log.e("VM", "appOp($tag) exception: ${e.message}", e)
                "执行失败: ${e.message}"
            }
            android.util.Log.d("VM", "appOp($tag) usb=$isUsbAdbActive result=${result.take(120)}")
            val trimmed = result.trim()
            if (trimmed.isBlank()) {
                notifier.complete(
                    id, appContext.getString(R.string.notif_op_failed, label),
                    "$name：${appContext.getString(R.string.apps_op_no_output)}"
                )
            } else {
                // pm 命令成功常返回 "Success"/"new state: disabled" 等，无需重复进内容行
                val extra = if (trimmed.equals("Success", true) || trimmed == "OK" ||
                    trimmed.equals("new state: enabled", true) ||
                    trimmed.equals("new state: disabled", true)
                ) "" else "：${trimmed.take(120)}"
                notifier.complete(id, appContext.getString(R.string.notif_op_success, label), "$name$extra")
            }
            onResult(trimmed)
            if (refreshApps) loadApps(force = true)
        }
    }

    fun uninstallApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "uninstallApp", pkg = pkg, opLabelRes = R.string.apps_action_uninstall,
        refreshApps = true,
        usb = { usbAdbRepository.uninstallApp(pkg) },
        wireless = { repository.uninstallApp(pkg) },
        onResult = onResult
    )

    /** 卸载但保留数据（pm uninstall -k），卸载后同样需要刷新列表 */
    fun uninstallAppKeepData(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "uninstallAppKeepData", pkg = pkg, opLabelRes = R.string.apps_action_uninstall_keep,
        refreshApps = true,
        usb = { usbAdbRepository.uninstallAppKeepData(pkg) },
        wireless = { repository.uninstallAppKeepData(pkg) },
        onResult = onResult
    )

    fun clearAppData(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "clearAppData", pkg = pkg, opLabelRes = R.string.apps_action_clear,
        usb = { usbAdbRepository.clearAppData(pkg) },
        wireless = { repository.clearAppData(pkg) },
        onResult = onResult
    )

    fun forceStopApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "forceStopApp", pkg = pkg, opLabelRes = R.string.apps_action_stop,
        usb = { usbAdbRepository.forceStopApp(pkg) },
        wireless = { repository.forceStopApp(pkg) },
        onResult = onResult
    )

    fun disableApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "disableApp", pkg = pkg, opLabelRes = R.string.apps_action_disable,
        refreshApps = true,
        usb = { usbAdbRepository.disableApp(pkg) },
        wireless = { repository.disableApp(pkg) },
        onResult = onResult
    )

    fun enableApp(pkg: String, onResult: (String) -> Unit) = appOp(
        tag = "enableApp", pkg = pkg, opLabelRes = R.string.apps_action_enable,
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
    ): ApkExtractResult {
        val id = OperationNotifier.idFor("$pkg:export")
        val name = appLabels.value[pkg]?.takeIf { it.isNotBlank() } ?: pkg
        val title = appContext.getString(R.string.apps_extract_running, name)
        return withContext(Dispatchers.IO) {
            try {
                notifier.start(id, title, null)
                val paths = if (isUsbAdbActive) {
                    usbAdbRepository.packageApkPaths(pkg)
                } else {
                    repository.packageApkPaths(pkg)
                }
                if (paths.isEmpty()) {
                    notifier.complete(id, appContext.getString(R.string.apps_extract_no_path), null)
                    return@withContext ApkExtractResult.NoApkPath
                }

                // 总大小用于百分比：单 APK 用精确大小；Split APK 落盘为 zip（含额外头/目录），
                // 实际写出量 ≠ 各 split 之和，故退化为"未知总量"避免进度超过 100%。
                val sizes = paths.map { remoteFileSize(it) }
                val total = if (paths.size == 1 && sizes.all { it > 0L }) sizes.sum() else -1L
                var writtenTotal = 0L
                var lastReported = 0L
                onProgress?.invoke(0L, total)

                val (dir, isPublic) = resolveExportDir()
                if (!dir.exists() && !dir.mkdirs()) {
                    notifier.complete(id, appContext.getString(R.string.apps_extract_failed, "无法创建目录"), null)
                    return@withContext ApkExtractResult.Failure("无法创建目录: ${dir.absolutePath}")
                }
                val fileName = if (paths.size == 1) "$pkg.apk" else "$pkg.apks"
                val dest = java.io.File(dir, fileName)

                // 用计数流包一层 sink：pullTo 无需改签名，进度按 PROGRESS_STEP 粒度上报，并同步刷新状态栏通知
                val pull: suspend (String, java.io.OutputStream) -> Boolean = { remote, sink ->
                    val counting = object : java.io.FilterOutputStream(sink) {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            super.write(b, off, len)
                            writtenTotal += len
                            if (writtenTotal - lastReported >= PROGRESS_STEP) {
                                lastReported = writtenTotal
                                onProgress?.invoke(writtenTotal, total)
                                notifier.progress(id, title, null, writtenTotal, total)
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
                    notifier.complete(id, appContext.getString(R.string.apps_extract_failed, reason), null)
                    return@withContext ApkExtractResult.Failure(reason)
                }
                // 收尾补一次终值到应用内进度条；状态栏通知直接发终态（不再多 post 一次 progress，
                // 避免与 complete 用同一 ID 在毫秒内连发被系统合并、终态被进行中帧覆盖）
                val finalTotal = if (total > 0L) total else writtenTotal
                onProgress?.invoke(writtenTotal, finalTotal)
                android.util.Log.d("VM", "exportApk ok: $fileName (${dest.length()} bytes, ${paths.size} apk)")
                val savedAt = if (isPublic) "Download/WearAdb" else dir.absolutePath
                notifier.complete(id, appContext.getString(R.string.apps_extract_saved, "$savedAt/$fileName"), null)
                ApkExtractResult.Success(
                    fileName = fileName,
                    location = savedAt,
                    fileCount = paths.size
                )
            } catch (e: Exception) {
                notifier.complete(id, appContext.getString(R.string.apps_extract_failed, e.message ?: "unknown error"), null)
                android.util.Log.e("VM", "exportApk exception: ${e.message}", e)
                ApkExtractResult.Failure(e.message ?: "unknown error")
            }
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

    // ── 安装状态栏通知辅助 ──
    private fun installNotifyStart(id: Int, content: String?) =
        notifier.start(id, appContext.getString(R.string.notif_install_in_progress), content)

    private fun installNotifyEnd(id: Int, result: String) {
        if (result.isBlank()) {
            notifier.complete(
                id, appContext.getString(R.string.notif_install_failed, appContext.getString(R.string.apps_op_no_output)), null
            )
        } else {
            notifier.complete(id, appContext.getString(R.string.notif_install_done), result.take(160))
        }
    }

    fun installApk(apkData: ByteArray, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val id = OperationNotifier.idFor("install:bytearray")
            installNotifyStart(id, null)
            onResult("正在安装...")
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.installApk(apkData)
            } else {
                repository.installApk(apkData)
            }
            installNotifyEnd(id, result)
            onResult(result)
            loadApps(force = true)
        }
    }

    fun installSplitApk(apkFiles: List<Pair<String, ByteArray>>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val id = OperationNotifier.idFor("install:split:${apkFiles.size}")
            installNotifyStart(id, null)
            onResult("正在安装 Split APK (${apkFiles.size} 个文件)...")
            val result = if (isUsbAdbActive) {
                usbAdbRepository.installSplitApk(apkFiles)
            } else {
                repository.installSplitApk(apkFiles)
            }
            installNotifyEnd(id, result)
            onResult(result)
            loadApps(force = true)
        }
    }

    fun installApkFile(apkFile: java.io.File, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val id = OperationNotifier.idFor("install:file:${apkFile.absolutePath}")
            installNotifyStart(id, apkFile.name)
            onResult("正在安装...")
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                usbAdbRepository.installApk(apkFile)
            } else {
                repository.installApk(apkFile)
            }
            installNotifyEnd(id, result)
            onResult(result)
            loadApps(force = true)
        }
    }

    /** 同步版本：等待安装完成后才返回，用于临时文件需要在安装期间保持存在的场景 */
    suspend fun installApkFileSync(apkFile: java.io.File, onResult: (String) -> Unit) {
        val id = OperationNotifier.idFor("install:file:${apkFile.absolutePath}")
        installNotifyStart(id, apkFile.name)
        onResult("正在安装...")
        val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
            usbAdbRepository.installApk(apkFile)
        } else {
            repository.installApk(apkFile)
        }
        installNotifyEnd(id, result)
        onResult(result)
        loadApps(force = true)
    }

    fun installSplitApkFiles(apkFiles: List<Pair<String, java.io.File>>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val id = OperationNotifier.idFor("install:splitfiles:${apkFiles.size}")
            installNotifyStart(id, null)
            onResult("正在安装 Split APK (${apkFiles.size} 个文件)...")
            val result = if (isUsbAdbActive) {
                usbAdbRepository.installSplitApkFiles(apkFiles)
            } else {
                repository.installSplitApkFiles(apkFiles)
            }
            installNotifyEnd(id, result)
            onResult(result)
            loadApps(force = true)
        }
    }

    suspend fun installSplitApkFromApks(apksFile: java.io.File, onStatus: (String) -> Unit): String? {
        val id = OperationNotifier.idFor("install:apks:${apksFile.absolutePath}")
        return try {
            installNotifyStart(id, apksFile.name)
            onStatus("正在解析 .apks...")
            val result = if (isUsbAdbActive) {
                usbAdbRepository.installSplitApkFromApksFile(apksFile)
            } else {
                repository.installSplitApkFromApksFile(apksFile)
            }
            installNotifyEnd(id, result)
            onStatus(result)
            loadApps(force = true)
            result
        } catch (e: Exception) {
            val msg = "安装异常: ${e.message}"
            notifier.complete(id, appContext.getString(R.string.notif_install_failed, e.message ?: "unknown error"), null)
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

    // 画质/帧率选项：maxSize=0 不限制；bitRate 单位 bps；maxFps=0 不限制。
    //
    // maxSize 默认 1024：Wear OS 手表物理分辨率普遍 450~480（如 466x466），
    // scrcpy 的 max_size 是对**长边**的缩放上限，1024 已高于常见手表长边，
    // 因此不损失清晰度；而对宽屏/高分辨率设备它又能挡住"按原生长边编码"
    // 带来的冗余码率与解码负担（H.264 编码耗时、传输量、手机端解码压力
    // 都随像素数平方增长）。0（不限制）会让 1080p+ 设备把整个像素预算
    // 灌进 USB 2.0 的 ADB 通道，是启动慢与拖影的直接放大器。
    private val _mirrorMaxSize = MutableStateFlow(1024)
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

    /**
     * 会话重启互斥锁。点设置里的画质/开关会走 engine.startSafe（先 stop 再起新 server），
     * 同一时刻只允许一个重启在跑：否则快速连点两个选项会并发触发两次 startSafe，二者共享
     * engine 的 videoStream/controlStream/launchStream 可变字段且无互斥，runLoop 会同时起
     * 两个 server 互相踩流，导致会话损坏、断连后回不来。串行化后"最新设置胜出"且永不竞态。
     */
    private val mirrorRestartMutex = Mutex()

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
            // 与 restartMirrorIfRunning 共用同一把锁：避免"启动中点击设置"并发触发
            // 两次 startSafe，踩踏 engine 的共享流字段。
            mirrorRestartMutex.withLock {
                engine.startSafe(transport, surface, buildMirrorOptions())
            }
        }
    }

    fun stopMirror() {
        currentMirror().first.stop()
    }

    /** 变更画质/开关选项。
     *  - 熄屏 / 保持唤醒：**运行时**直接下发控制消息或 Android 全局设置，不重启会话（与官方 scrcpy MOD+o / --stay-awake 一致）。
     *  - 分辨率 / 码率 / 帧率：scrcpy 协议限制只能在 server 启动时指定，仍在投屏中用新参数无缝重启。
     *  - 只读：纯 UI 门控，不注入。
     *  值未变化（如点回当前已选项）直接跳过，避免无谓断流。 */
    fun setMirrorMaxSize(v: Int) {
        if (_mirrorMaxSize.value == v) return
        _mirrorMaxSize.value = v
        restartMirrorIfRunning()
    }

    fun setMirrorBitRate(v: Int) {
        if (_mirrorBitRate.value == v) return
        _mirrorBitRate.value = v
        restartMirrorIfRunning()
    }

    fun setMirrorMaxFps(v: Float) {
        if (_mirrorMaxFps.value == v) return
        _mirrorMaxFps.value = v
        restartMirrorIfRunning()
    }

    fun setMirrorReadOnly(v: Boolean) { _mirrorReadOnly.value = v }

    fun setMirrorTurnOffScreen(v: Boolean) {
        if (_mirrorTurnOffScreen.value == v) return
        _mirrorTurnOffScreen.value = v
        // 运行时切换：直接发 SET_DISPLAY_POWER 控制消息，无需重启会话（与官方 scrcpy MOD+o 一致）。
        // 未投屏时 sendControl 返回 false，仅保存状态、待下次启动生效。
        deviceOp({ usbAdbRepository.screenPowerOff(v) }, { repository.screenPowerOff(v) })
    }

    fun setMirrorStayAwake(v: Boolean) {
        if (_mirrorStayAwake.value == v) return
        _mirrorStayAwake.value = v
        // 运行时切换：直接写 Android 全局设置，无需重启会话（底层即 scrcpy 的 stay_awake 实现）。
        deviceOp({ usbAdbRepository.setStayAwake(v) }, { repository.setStayAwake(v) })
    }

    private fun restartMirrorIfRunning() {
        val sf = mirrorSurface ?: return
        // 按**当前通道**取状态：此前固定读 usbAdbRepository.mirrorStatus，无线通道下
        // 该值永远是 Idle → 直接 return → 改画质/帧率/熄屏都不生效（真功能 bug）。
        // currentMirror() 已按 isUsbAdbActive 选好 engine，用它自己的 status 判断。
        val (engine, transport) = currentMirror()
        // 锁内再取最新参数与状态：串行化重启，杜绝并发两次 startSafe 踩踏共享流字段。
        viewModelScope.launch(Dispatchers.IO) {
            mirrorRestartMutex.withLock {
                val st = engine.status.value
                if (st !is com.wearadb.adb.MirrorStatus.Streaming &&
                    st !is com.wearadb.adb.MirrorStatus.Starting
                ) return@withLock
                engine.startSafe(transport, sf, buildMirrorOptions())
            }
        }
    }

    fun tap(x: Int, y: Int) =
        deviceOp({ usbAdbRepository.tap(x, y) }, { repository.tap(x, y) })

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300) =
        deviceOp({ usbAdbRepository.swipe(x1, y1, x2, y2, dur) }, { repository.swipe(x1, y1, x2, y2, dur) })

    fun keyEvent(code: Int) =
        deviceOp({ usbAdbRepository.keyEvent(code) }, { repository.keyEvent(code) })

    // ── 投屏实时注入（scrcpy control 通道；未就绪时仓储内部回退 input 命令） ──
    // 走独立协程而非 deviceOp：拖动会在几十毫秒内连发几十条，串行派发会让
    // 设备侧手势"粘住"。control 通道本身是线程安全的单向写，直接并发即可。

    /** 触摸按下。回退路径（无 control 通道）用整段"点击"近似，抬手时不再补发。 */
    fun touchDown(x: Int, y: Int, pointerId: Long) = launchWithMirrorSize { w, h ->
        if (isUsbAdbActive) usbAdbRepository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_DOWN, x, y, w, h, pointerId
        ) { usbAdbRepository.tap(x, y) }
        else repository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_DOWN, x, y, w, h, pointerId
        ) { repository.tap(x, y) }
    }

    /** 触摸移动（逐帧注入，这就是"跟手"的来源）。 */
    fun touchMove(x: Int, y: Int, pointerId: Long) = launchWithMirrorSize { w, h ->
        if (isUsbAdbActive) usbAdbRepository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_MOVE, x, y, w, h, pointerId
        )
        else repository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_MOVE, x, y, w, h, pointerId
        )
    }

    /** 触摸抬起。DOWN 与 UP 必须成对，否则设备侧手指会一直按着。 */
    fun touchUp(x: Int, y: Int, pointerId: Long) = launchWithMirrorSize { w, h ->
        if (isUsbAdbActive) usbAdbRepository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_UP, x, y, w, h, pointerId
        )
        else repository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_UP, x, y, w, h, pointerId
        )
    }

    /** 手势被系统打断（返回手势等）时补发 CANCEL，避免设备侧残留按下状态。 */
    fun touchCancel(pointerId: Long) = launchWithMirrorSize { w, h ->
        val x = w / 2
        val y = h / 2
        if (isUsbAdbActive) usbAdbRepository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_CANCEL, x, y, w, h, pointerId
        )
        else repository.touchInject(
            com.wearadb.adb.ScrcpyControlProtocol.ACTION_CANCEL, x, y, w, h, pointerId
        )
    }

    /** 投屏页按键/文本注入：control 通道优先，失败回退 input 命令（与 tap/swipe 同策略）。 */
    fun mirrorKeyEvent(code: Int) =
        deviceOp({ usbAdbRepository.keyInject(code) }, { repository.keyInject(code) })

    fun mirrorInputText(text: String) =
        deviceOp({ usbAdbRepository.textInject(text) }, { repository.textInject(text) })

    fun mirrorRotate() =
        deviceOp({ usbAdbRepository.rotateInject() }, { repository.rotateInject() })

    /**
     * 触摸注入单车道（并发度 = 1），保证 DOWN / MOVE / UP **严格按序**下发。
     *
     * 【为什么必须串行】触摸是**有状态、顺序敏感**的协议序列，而 libadb 的
     * `AdbStream.write()` 用**非公平监视器**分配写令牌（`while (!mWriteReady…) wait();`
     * 的唤醒顺序不保证 FIFO）。此前每个触摸事件各起一条协程跑在多线程的
     * `Dispatchers.IO` 上，MOVE 会先于 DOWN、或彼此乱序到达设备——设备只会把这些
     * 当成一堆孤立的触摸点，表现为「能点击、不能滑动」。
     * 官方 scrcpy 客户端是单线程按序 push 每条 control 消息，这里对齐其语义。
     *
     * 单车道同时消除了并发写，使 [com.wearadb.adb.ScreenMirrorEngine.sendControl]
     * 的在途检测在正常触摸路径下永不触发。
     */
    private val mirrorTouchDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * 注入需要设备真实分辨率：UI 的 mapToDevice 输出真实坐标，`input` 回退命令也用
     * 真实坐标。scrcpy control 线协议所需的**视频分辨率** w/h 与视频空间坐标，
     * 由仓储层 touchInject 内部换算（服务端硬校验 w/h 必须等于视频头尺寸）。
     * 尺寸还没探测到就直接跳过——此时画面都还没出来，注入没有意义。
     */
    private fun launchWithMirrorSize(block: suspend (Int, Int) -> Unit) {
        val real = mirrorRealSize.value ?: return
        viewModelScope.launch(mirrorTouchDispatcher) {
            try {
                block(real.first, real.second)
            } catch (e: Exception) {
                android.util.Log.w("VM", "mirror inject failed: ${e.message}")
            }
        }
    }

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
