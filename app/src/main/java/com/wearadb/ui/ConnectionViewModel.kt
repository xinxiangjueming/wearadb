package com.wearadb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.data.repository.ConnectionState
import com.wearadb.data.repository.DiscoveredDevice
import com.wearadb.data.repository.PullResult
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

    // ── Routing helper ──
    private val isUsbAdbActive: Boolean
        get() = usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED

    private fun usbAdbCmd(command: String) {
        viewModelScope.launch { usbAdbRepository.executeCommand(command) }
    }

    // ── Connection ──
    fun connect(host: String, port: Int = 55555, useTls: Boolean = false) {
        android.util.Log.d("VM", "connect() START host=$host port=$port useTls=$useTls current=${connectionState.value}")
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
            } catch (e: Exception) {
                android.util.Log.e("VM", "loadApps() EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            }
            _appsLoading.value = false
        }
    }

    fun setAppsFilter(filter: AppFilter) { _appsFilter.value = filter }

    fun uninstallApp(pkg: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.uninstallApp(pkg).trim()); loadApps(force = true) }
    }

    fun clearAppData(pkg: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.clearAppData(pkg).trim()) }
    }

    fun forceStopApp(pkg: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.forceStopApp(pkg).trim()) }
    }

    fun disableApp(pkg: String, onResult: (String) -> Unit) {
        android.util.Log.d("VM", "disableApp() pkg=$pkg")
        viewModelScope.launch {
            val result = repository.disableApp(pkg).trim()
            android.util.Log.d("VM", "disableApp() result: $result")
            onResult(result)
        }
    }

    fun enableApp(pkg: String, onResult: (String) -> Unit) {
        android.util.Log.d("VM", "enableApp() pkg=$pkg")
        viewModelScope.launch {
            val result = repository.enableApp(pkg).trim()
            android.util.Log.d("VM", "enableApp() result: $result")
            onResult(result)
        }
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
            val result = repository.installSplitApk(apkFiles)
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
            val result = repository.installSplitApkFiles(apkFiles)
            onResult(result)
            loadApps(force = true)
        }
    }

    suspend fun installSplitApkFromApks(apksFile: java.io.File, onStatus: (String) -> Unit): String? {
        return try {
            onStatus("正在解析 .apks...")
            val result = repository.installSplitApkFromApksFile(apksFile)
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
            val result = if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                // USB: 写临时文件再推送
                val tmpFile = java.io.File(appContext.cacheDir, "wearadb_push_tmp")
                tmpFile.writeBytes(data)
                val r = usbAdbRepository.pushFile(tmpFile, remotePath)
                tmpFile.delete()
                r
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
            if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                val (success, data) = usbAdbRepository.pullFile(remotePath)
                onResult(PullResult(success, data, if (success) "拉取成功: $remotePath" else "拉取失败"))
            } else {
                onResult(repository.pullFile(remotePath))
            }
        }
    }

    // ── Advanced Ops ──
    fun reboot(mode: String = "") {
        if (isUsbAdbActive) {
            val cmd = when (mode) {
                "recovery" -> "reboot recovery"
                "bootloader" -> "reboot bootloader"
                "shutdown" -> "reboot -p"
                else -> "reboot"
            }
            viewModelScope.launch {
                usbAdbRepository.executeCommand(cmd)
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

    fun tap(x: Int, y: Int) {
        if (isUsbAdbActive) usbAdbCmd("input tap $x $y")
        else viewModelScope.launch { repository.tap(x, y) }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300) {
        if (isUsbAdbActive) usbAdbCmd("input swipe $x1 $y1 $x2 $y2 $dur")
        else viewModelScope.launch { repository.swipe(x1, y1, x2, y2, dur) }
    }

    fun keyEvent(code: Int) {
        if (isUsbAdbActive) usbAdbCmd("input keyevent $code")
        else viewModelScope.launch { repository.keyEvent(code) }
    }

    fun enableWifi() {
        if (isUsbAdbActive) usbAdbCmd("svc wifi enable")
        else viewModelScope.launch { repository.enableWifi() }
    }
    fun disableWifi() {
        if (isUsbAdbActive) usbAdbCmd("svc wifi disable")
        else viewModelScope.launch { repository.disableWifi() }
    }
    fun enableBluetooth() {
        if (isUsbAdbActive) usbAdbCmd("svc bluetooth enable")
        else viewModelScope.launch { repository.enableBluetooth() }
    }
    fun disableBluetooth() {
        if (isUsbAdbActive) usbAdbCmd("svc bluetooth disable")
        else viewModelScope.launch { repository.disableBluetooth() }
    }
    fun volumeUp() {
        if (isUsbAdbActive) usbAdbCmd("input keyevent 24")
        else viewModelScope.launch { repository.volumeUp() }
    }
    fun volumeDown() {
        if (isUsbAdbActive) usbAdbCmd("input keyevent 25")
        else viewModelScope.launch { repository.volumeDown() }
    }
    fun volumeMute() {
        if (isUsbAdbActive) usbAdbCmd("input keyevent 164")
        else viewModelScope.launch { repository.volumeMute() }
    }
    fun screenOn() {
        if (isUsbAdbActive) usbAdbCmd("input keyevent 26")
        else viewModelScope.launch { repository.screenOn() }
    }
    fun screenOff() {
        if (isUsbAdbActive) usbAdbCmd("input keyevent 26")
        else viewModelScope.launch { repository.screenOff() }
    }
    fun inputText(text: String) {
        if (isUsbAdbActive) usbAdbCmd("input text \"$text\"")
        else viewModelScope.launch { repository.inputText(text) }
    }

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
