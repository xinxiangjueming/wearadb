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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for wireless ADB operations + routing through USB ADB.
 * Manages connection, shell, device info, apps, files, and advanced ops.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
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

    // ── Last IP ──
    private val _lastHost = MutableStateFlow("")
    val lastHost: StateFlow<String> = _lastHost.asStateFlow()
    private val _lastPort = MutableStateFlow(5555)
    val lastPort: StateFlow<Int> = _lastPort.asStateFlow()

    // ── Operation result ──
    private val _opResult = MutableSharedFlow<String>()
    val opResult: SharedFlow<String> = _opResult.asSharedFlow()

    init {
        viewModelScope.launch {
            _lastHost.value = repository.getLastHost()
            _lastPort.value = repository.getLastPort()
        }
        // Clear loaded data when connection drops
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    _apps.value = emptyList()
                    _files.value = emptyList()
                    _deviceInfo.value = null
                    _currentPath.value = "/sdcard"
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
    fun connect(host: String, port: Int = 5555, useTls: Boolean = false) {
        viewModelScope.launch {
            try {
                repository.connect(host, port, useTls)
                if (repository.connectionState.value == ConnectionState.CONNECTED) {
                    _showBluetoothDialog.value = true
                }
            } catch (_: Exception) {}
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
        connect(device.host, device.port, useTls = !device.isPairing)
    }

    // ── Pairing ──
    fun pair(host: String, port: Int, code: String) {
        viewModelScope.launch {
            _pairingState.value = PairingState.Pairing
            val result = repository.pair(host, port, code)
            if (result.success) {
                _pairingState.value = PairingState.Success(result.message)
                if (result.port > 0 && result.port != port) {
                    kotlinx.coroutines.delay(500)
                    connect(result.host, result.port, useTls = true)
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
                            _shellOutput.value = sb.toString()
                        } else {
                            kotlinx.coroutines.delay(50)
                        }
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
            for (cmd in commands) {
                try {
                    val result = if (connectionState.value == ConnectionState.CONNECTED) {
                        val stream = repository.openShell(cmd)
                        val os = stream.openOutputStream()
                        os.write("$cmd\n".toByteArray())
                        os.flush()
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream.openInputStream()))
                        val sb = StringBuilder()
                        val startTime = System.currentTimeMillis()
                        while (System.currentTimeMillis() - startTime < 10000) {
                            if (reader.ready()) {
                                val line = reader.readLine() ?: break
                                sb.appendLine(line)
                            } else {
                                kotlinx.coroutines.delay(50)
                            }
                        }
                        sb.toString().ifEmpty { "(无输出)" }
                    } else if (isUsbAdbActive) {
                        usbAdbRepository.executeCommand(cmd)
                    } else {
                        "未连接"
                    }
                    results.appendLine(result)
                } catch (e: Exception) {
                    results.appendLine("Error: ${e.message}")
                }
            }
            _shellOutput.value = results.toString()
        }
    }

    // ── Device Info ──
    fun loadDeviceInfo(force: Boolean = false) {
        viewModelScope.launch {
            if (!force && _deviceInfo.value != null) return@launch
            _deviceInfoLoading.value = true
            try {
                if (connectionState.value == ConnectionState.CONNECTED) {
                    _deviceInfo.value = repository.getDeviceInfo()
                } else if (isUsbAdbActive) {
                    _deviceInfo.value = usbAdbRepository.getDeviceInfo()
                }
            } catch (_: Exception) {}
            _deviceInfoLoading.value = false
        }
    }

    // ── Apps ──
    fun loadApps(force: Boolean = false) {
        viewModelScope.launch {
            if (!force && appsLoadedOnce && _apps.value.isNotEmpty()) return@launch
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
            } catch (_: Exception) {}
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
        viewModelScope.launch { onResult(repository.disableApp(pkg).trim()) }
    }

    fun enableApp(pkg: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.enableApp(pkg).trim()) }
    }

    fun installApk(apkData: ByteArray, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("正在安装...")
            val result = repository.installApk(apkData)
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
            val result = repository.installApk(apkFile)
            onResult(result)
            loadApps(force = true)
        }
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
            try { _files.value = repository.listFiles(path) } catch (_: Exception) { _files.value = emptyList() }
            _filesLoading.value = false
        }
    }

    fun navigateToPath(path: String) = loadFiles(path)

    fun navigateUp() {
        val parent = _currentPath.value.substringBeforeLast("/", "/")
        loadFiles(parent)
    }

    fun deleteFile(path: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.deleteFile(path).trim()); loadFiles(force = true) }
    }

    fun createDirectory(path: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.createDirectory(path).trim()); loadFiles(force = true) }
    }

    fun readFile(path: String, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.readFile(path)) }
    }

    fun pushFile(data: ByteArray, remotePath: String, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.pushFile(data, remotePath)
            _opResult.emit(result)
            onResult?.invoke(result)
            loadFiles(force = true)
        }
    }

    fun pullFile(remotePath: String, onResult: (PullResult) -> Unit) {
        viewModelScope.launch { onResult(repository.pullFile(remotePath)) }
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
            usbAdbCmd(cmd)
        } else {
            viewModelScope.launch {
                when (mode) {
                    "recovery" -> repository.rebootRecovery()
                    "bootloader" -> repository.rebootBootloader()
                    "shutdown" -> repository.shutdown()
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

enum class AppFilter { ALL, SYSTEM, THIRD_PARTY }

sealed class PairingState {
    data object Idle : PairingState()
    data object Pairing : PairingState()
    data class Success(val message: String) : PairingState()
    data class Error(val message: String) : PairingState()
}
