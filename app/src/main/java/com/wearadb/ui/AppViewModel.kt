package com.wearadb.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.data.repository.ConnectionState
import com.wearadb.data.repository.DiscoveredDevice
import com.wearadb.data.repository.PullResult
import com.wearadb.data.model.*
import com.wearadb.data.repository.AdbRepository
import io.github.muntashirakon.adb.AdbStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: AdbRepository
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

    // Last IP
    private val _lastHost = MutableStateFlow("")
    val lastHost: StateFlow<String> = _lastHost.asStateFlow()
    private val _lastPort = MutableStateFlow(5555)
    val lastPort: StateFlow<Int> = _lastPort.asStateFlow()

    // Operation result
    private val _opResult = MutableSharedFlow<String>()
    val opResult: SharedFlow<String> = _opResult.asSharedFlow()

    private var shellStream: AdbStream? = null

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
            try { repository.connect(host, port, useTls) } catch (_: Exception) {}
        }
    }

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
                // 关闭旧的 shell 流
                try { shellStream?.close() } catch (_: Exception) {}
                shellStream = null

                // 打开新的 shell 流并执行命令
                val stream = repository.openShell(command)
                shellStream = stream

                val os = stream.openOutputStream()
                os.write("$command\n".toByteArray())
                os.flush()

                // 读取输出
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
            } catch (e: Exception) {
                _shellOutput.value = "Error: ${e.message}"
            }
        }
    }

    // ── Device Info ──
    fun loadDeviceInfo(force: Boolean = false) {
        viewModelScope.launch {
            if (!force && _deviceInfo.value != null) return@launch
            _deviceInfoLoading.value = true
            try { _deviceInfo.value = repository.getDeviceInfo() } catch (_: Exception) {}
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
                _apps.value = repository.getInstalledPackages()
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
    fun reboot(mode: String = "") {
        viewModelScope.launch {
            when (mode) {
                "recovery" -> repository.rebootRecovery()
                "bootloader" -> repository.rebootBootloader()
                "shutdown" -> repository.shutdown()
                else -> repository.reboot()
            }
        }
    }

    fun takeScreenshot() {
        viewModelScope.launch {
            _screenshotLoading.value = true
            _screenshotData.value = repository.screenshot()
            _screenshotLoading.value = false
        }
    }

    fun clearScreenshot() { _screenshotData.value = null }

    fun tap(x: Int, y: Int) {
        viewModelScope.launch { repository.tap(x, y) }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300) {
        viewModelScope.launch { repository.swipe(x1, y1, x2, y2, dur) }
    }

    fun keyEvent(code: Int) {
        viewModelScope.launch { repository.keyEvent(code) }
    }

    fun enableWifi() { viewModelScope.launch { repository.enableWifi() } }
    fun disableWifi() { viewModelScope.launch { repository.disableWifi() } }
    fun enableBluetooth() { viewModelScope.launch { repository.enableBluetooth() } }
    fun disableBluetooth() { viewModelScope.launch { repository.disableBluetooth() } }
    fun volumeUp() { viewModelScope.launch { repository.volumeUp() } }
    fun volumeDown() { viewModelScope.launch { repository.volumeDown() } }
    fun volumeMute() { viewModelScope.launch { repository.volumeMute() } }
    fun screenOn() { viewModelScope.launch { repository.screenOn() } }
    fun screenOff() { viewModelScope.launch { repository.screenOff() } }
    fun inputText(text: String) { viewModelScope.launch { repository.inputText(text) } }

    fun removeDevice(address: String) { viewModelScope.launch { repository.removeDevice(address) } }
    fun toggleFavorite(address: String) { viewModelScope.launch { repository.toggleFavorite(address) } }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        repository.destroy()
    }
}

enum class AppFilter { ALL, SYSTEM, THIRD_PARTY }

sealed class PairingState {
    data object Idle : PairingState()
    data object Pairing : PairingState()
    data class Success(val message: String) : PairingState()
    data class Error(val message: String) : PairingState()
}
