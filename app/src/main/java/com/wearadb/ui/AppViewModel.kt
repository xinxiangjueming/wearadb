package com.wearadb.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.data.repository.ConnectionState
import com.wearadb.data.repository.DiscoveredDevice
import com.wearadb.data.repository.PullResult
import com.wearadb.data.model.*
import com.wearadb.data.repository.AdbRepository
import com.wearadb.fastboot.FastbootConnectionState
import com.wearadb.fastboot.FastbootDevice
import com.wearadb.fastboot.FastbootRepository
import com.wearadb.adb.UsbAdbRepository
import com.wearadb.adb.UsbAdbConnectionState
import com.wearadb.adb.UsbAdbDeviceInfo
import io.github.muntashirakon.adb.AdbStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: AdbRepository,
    private val fastbootRepository: FastbootRepository,
    private val usbAdbRepository: UsbAdbRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val deviceBanner: StateFlow<String> = repository.deviceBanner
    val devices: Flow<List<SavedDevice>> = repository.devices

    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput.asStateFlow()

    // Device Info
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()
    private val _deviceInfoLoading = MutableStateFlow(false)
    val deviceInfoLoading: StateFlow<Boolean> = _deviceInfoLoading.asStateFlow()

    // Apps
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()
    private val _appsFilter = MutableStateFlow(AppFilter.ALL)
    val appsFilter: StateFlow<AppFilter> = _appsFilter.asStateFlow()

    // Files
    private val _files = MutableStateFlow<List<FileEntry>>(emptyList())
    val files: StateFlow<List<FileEntry>> = _files.asStateFlow()
    private val _currentPath = MutableStateFlow("/sdcard")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    private val _filesLoading = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = _filesLoading.asStateFlow()

    // NSD Discovery
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = repository.discoveredDevices
    val isDiscovering: StateFlow<Boolean> = repository.isDiscovering

    // Pairing
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    // Screenshot
    private val _screenshotData = MutableStateFlow<ByteArray?>(null)
    val screenshotData: StateFlow<ByteArray?> = _screenshotData.asStateFlow()
    private val _screenshotLoading = MutableStateFlow(false)
    val screenshotLoading: StateFlow<Boolean> = _screenshotLoading.asStateFlow()

    // Bluetooth dialog
    private val _showBluetoothDialog = MutableStateFlow(false)
    val showBluetoothDialog: StateFlow<Boolean> = _showBluetoothDialog.asStateFlow()

    // Last IP
    private val _lastHost = MutableStateFlow("")
    val lastHost: StateFlow<String> = _lastHost.asStateFlow()
    private val _lastPort = MutableStateFlow(5555)
    val lastPort: StateFlow<Int> = _lastPort.asStateFlow()

    // Operation result
    private val _opResult = MutableSharedFlow<String>()
    val opResult: SharedFlow<String> = _opResult.asSharedFlow()

    private var shellStream: AdbStream? = null

    // ── Fastboot ──
    val fastbootConnectionState: StateFlow<FastbootConnectionState> = fastbootRepository.connectionState
    val fastbootConnectedDevice: StateFlow<FastbootDevice?> = fastbootRepository.connectedDevice
    val fastbootConnectLog: StateFlow<String> = fastbootRepository.connectLog

    private val _fastbootDevices = MutableStateFlow<List<FastbootDevice>>(emptyList())
    val fastbootDevices: StateFlow<List<FastbootDevice>> = _fastbootDevices.asStateFlow()

    private val _fastbootInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val fastbootInfo: StateFlow<Map<String, String>> = _fastbootInfo.asStateFlow()

    private val _fastbootResult = MutableSharedFlow<String>()
    val fastbootResult: SharedFlow<String> = _fastbootResult.asSharedFlow()

    private val _fastbootFlashProgress = MutableStateFlow(-1)
    val fastbootFlashProgress: StateFlow<Int> = _fastbootFlashProgress.asStateFlow()

    init {
        viewModelScope.launch {
            _lastHost.value = repository.getLastHost()
            _lastPort.value = repository.getLastPort()
        }
        // 监听连接状态，断开时清空已加载数据
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
        // NSD _adb-tls-connect services require TLS; _adb-tls-pairing does not
        connect(device.host, device.port, useTls = !device.isPairing)
    }

    // ── Pairing ──
    fun pair(host: String, port: Int, code: String) {
        viewModelScope.launch {
            _pairingState.value = PairingState.Pairing
            val result = repository.pair(host, port, code)
            if (result.success) {
                _pairingState.value = PairingState.Success(result.message)
                // Auto-connect using the port returned by the device (TLS required)
                if (result.port > 0 && result.port != port) {
                    kotlinx.coroutines.delay(500)
                    connect(result.host, result.port, useTls = true)
                }
            } else {
                _pairingState.value = PairingState.Error(result.message)
            }
        }
    }

    fun resetPairingState() {
        _pairingState.value = PairingState.Idle
    }

    // ── Shell ──
    fun executeCommand(command: String) {
        viewModelScope.launch {
            try {
                if (connectionState.value == ConnectionState.CONNECTED) {
                    // 无线ADB：流式读取
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
                } else if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                    // USB ADB：一次性执行
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

    /** 批量执行多条命令，结果合并显示 */
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
                    } else if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
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
                } else if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
                    _deviceInfo.value = usbAdbRepository.getDeviceInfo()
                }
            } catch (_: Exception) {}
            _deviceInfoLoading.value = false
        }
    }

    // ── Apps ──
    private var appsLoadedOnce = false
    fun loadApps(force: Boolean = false) {
        viewModelScope.launch {
            if (!force && appsLoadedOnce && _apps.value.isNotEmpty()) return@launch
            _appsLoading.value = true
            try {
                _apps.value = if (connectionState.value == ConnectionState.CONNECTED) {
                    repository.getInstalledPackages()
                } else if (usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED) {
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

    // File 版安装（避免 OOM）
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

    // .apks 流式安装（避免 OOM：逐个解压+推送，不同时加载全部到内存）
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

    // ── File Push/Pull ──
    fun pushFile(data: ByteArray, remotePath: String, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.pushFile(data, remotePath)
            _opResult.emit(result)
            onResult?.invoke(result)
            loadFiles(force = true)
        }
    }

    fun pullFile(remotePath: String, onResult: (PullResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.pullFile(remotePath)
            onResult(result)
        }
    }

    // ── Advanced Ops ──

    /** Whether USB ADB is the active connection. */
    private val isUsbAdbActive: Boolean
        get() = usbAdbConnectionState.value == UsbAdbConnectionState.CONNECTED

    /** Execute a shell command on USB ADB. */
    private fun usbAdbCmd(command: String) {
        viewModelScope.launch { usbAdbRepository.executeCommand(command) }
    }

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
                null // USB ADB screenshot not supported yet
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

    // ── Fastboot ──

    fun scanFastbootDevices() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _fastbootDevices.value = fastbootRepository.scanDevices()
        }
    }

    fun connectFastboot(device: FastbootDevice) {
        viewModelScope.launch {
            try {
                val success = fastbootRepository.connect(device)
                if (success) {
                    loadFastbootInfo()
                } else {
                    _fastbootResult.emit("连接失败: 请检查手机是否弹出了USB权限对话框并点击允许。如果没有弹窗，试试拔掉USB线重新插入后再连接。查看Logcat(FastbootManager)可获取详细日志。")
                }
            } catch (e: Exception) {
                _fastbootResult.emit("连接异常: ${e.message}")
            }
        }
    }

    fun disconnectFastboot() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                fastbootRepository.disconnect()
            } catch (_: Exception) {}
            _fastbootInfo.value = emptyMap()
        }
    }

    private fun loadFastbootInfo() {
        viewModelScope.launch {
            try {
                _fastbootInfo.value = fastbootRepository.getDeviceInfo()
            } catch (e: Exception) {
                _fastbootResult.emit("获取设备信息失败: ${e.message}")
            }
        }
    }

    fun fastbootReboot() {
        viewModelScope.launch {
            val result = fastbootRepository.reboot()
            _fastbootResult.emit(result)
            disconnectFastboot()
        }
    }

    fun fastbootRebootRecovery() {
        viewModelScope.launch {
            val result = fastbootRepository.rebootRecovery()
            _fastbootResult.emit(result)
            disconnectFastboot()
        }
    }

    fun fastbootRebootBootloader() {
        viewModelScope.launch {
            val result = fastbootRepository.rebootBootloader()
            _fastbootResult.emit(result)
            // 重启到 bootloader 后重新扫描
            kotlinx.coroutines.delay(2000)
            scanFastbootDevices()
        }
    }

    fun fastbootFlash(partition: String, data: ByteArray) {
        viewModelScope.launch {
            _fastbootFlashProgress.value = 0
            val result = fastbootRepository.flash(partition, data) { progress ->
                _fastbootFlashProgress.value = progress
            }
            _fastbootResult.emit(result)
            _fastbootFlashProgress.value = -1
        }
    }

    fun fastbootErase(partition: String) {
        viewModelScope.launch {
            val result = fastbootRepository.erase(partition)
            _fastbootResult.emit(result)
        }
    }

    fun fastbootOem(command: String) {
        viewModelScope.launch {
            val result = fastbootRepository.oem(command)
            _fastbootResult.emit(result)
        }
    }

    fun loadFastbootVarAll() {
        viewModelScope.launch {
            _fastbootInfo.value = fastbootRepository.getVarAll()
        }
    }

    fun fastbootFlashingUnlock() {
        viewModelScope.launch {
            val result = fastbootRepository.flashingUnlock()
            _fastbootResult.emit(result)
        }
    }

    fun fastbootFlashingLock() {
        viewModelScope.launch {
            val result = fastbootRepository.flashingLock()
            _fastbootResult.emit(result)
        }
    }

    fun fastbootBoot(data: ByteArray) {
        viewModelScope.launch {
            _fastbootFlashProgress.value = 0
            val result = fastbootRepository.boot(data) { progress ->
                _fastbootFlashProgress.value = progress
            }
            _fastbootResult.emit(result)
            _fastbootFlashProgress.value = -1
        }
    }

    fun fastbootStage(data: ByteArray) {
        viewModelScope.launch {
            _fastbootFlashProgress.value = 0
            val result = fastbootRepository.stage(data) { progress ->
                _fastbootFlashProgress.value = progress
            }
            _fastbootResult.emit(result)
            _fastbootFlashProgress.value = -1
        }
    }

    fun fastbootFetch() {
        viewModelScope.launch {
            val (message, data) = fastbootRepository.fetch()
            _fastbootResult.emit(message)
            // data 可以保存到文件，这里只提示大小
        }
    }

    // ── 有线 USB ADB ──

    val usbAdbConnectionState: StateFlow<UsbAdbConnectionState> = usbAdbRepository.connectionState
    val usbAdbConnectedDevice: StateFlow<UsbAdbDeviceInfo?> = usbAdbRepository.connectedDevice
    val usbAdbConnectLog: StateFlow<String> = usbAdbRepository.connectLog

    private val _usbAdbDevices = MutableStateFlow<List<UsbAdbDeviceInfo>>(emptyList())
    val usbAdbDevices: StateFlow<List<UsbAdbDeviceInfo>> = _usbAdbDevices.asStateFlow()

    private val _usbAdbShellOutput = MutableStateFlow("")
    val usbAdbShellOutput: StateFlow<String> = _usbAdbShellOutput.asStateFlow()

    private val _usbAdbResult = MutableSharedFlow<String>()
    val usbAdbResult: SharedFlow<String> = _usbAdbResult.asSharedFlow()

    fun scanUsbAdbDevices() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _usbAdbDevices.value = usbAdbRepository.scanDevices()
        }
    }

    fun connectUsbAdb(deviceInfo: UsbAdbDeviceInfo) {
        viewModelScope.launch {
            val success = usbAdbRepository.connect(deviceInfo)
            if (success) {
                loadDeviceInfo(force = true)
            }
        }
    }

    fun disconnectUsbAdb() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            usbAdbRepository.disconnect()
            _deviceInfo.value = null
            _usbAdbShellOutput.value = ""
        }
    }

    fun executeUsbAdbCommand(command: String) {
        viewModelScope.launch {
            _usbAdbShellOutput.value = "执行中..."
            val result = usbAdbRepository.executeCommand(command)
            _usbAdbShellOutput.value = result
        }
    }

    // ── 活动ADB连接检查 ──

    /** 是否有任何ADB连接（无线或有线） */
    val isAnyAdbConnected: StateFlow<Boolean> = combine(
        connectionState, usbAdbConnectionState
    ) { wireless, usb ->
        wireless == ConnectionState.CONNECTED || usb == UsbAdbConnectionState.CONNECTED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    override fun onCleared() {
        super.onCleared()
        // Don't destroy singletons here — they outlive the ViewModel.
        // Only disconnect the wireless ADB stream (non-singleton resource).
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
