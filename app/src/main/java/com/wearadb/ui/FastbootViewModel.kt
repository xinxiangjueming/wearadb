package com.wearadb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.fastboot.FastbootConnectionState
import com.wearadb.fastboot.FastbootDevice
import com.wearadb.fastboot.FastbootRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Fastboot operations (USB bootloader mode).
 */
@HiltViewModel
class FastbootViewModel @Inject constructor(
    private val fastbootRepository: FastbootRepository
) : ViewModel() {

    val connectionState: StateFlow<FastbootConnectionState> = fastbootRepository.connectionState
    val connectedDevice: StateFlow<FastbootDevice?> = fastbootRepository.connectedDevice
    val connectLog: StateFlow<String> = fastbootRepository.connectLog

    private val _devices = MutableStateFlow<List<FastbootDevice>>(emptyList())
    val devices: StateFlow<List<FastbootDevice>> = _devices.asStateFlow()

    private val _info = MutableStateFlow<Map<String, String>>(emptyMap())
    val info: StateFlow<Map<String, String>> = _info.asStateFlow()

    private val _result = MutableSharedFlow<String>()
    val result: SharedFlow<String> = _result.asSharedFlow()

    private val _flashProgress = MutableStateFlow(-1)
    val flashProgress: StateFlow<Int> = _flashProgress.asStateFlow()

    fun scanDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            _devices.value = fastbootRepository.scanDevices()
        }
    }

    fun connect(device: FastbootDevice) {
        viewModelScope.launch {
            try {
                val success = fastbootRepository.connect(device)
                if (success) {
                    loadInfo()
                } else {
                    _result.emit("连接失败: 请检查手机是否弹出了USB权限对话框并点击允许。如果没有弹窗，试试拔掉USB线重新插入后再连接。查看Logcat(FastbootManager)可获取详细日志。")
                }
            } catch (e: Exception) {
                _result.emit("连接异常: ${e.message}")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            try { fastbootRepository.disconnect() } catch (_: Exception) {}
            _info.value = emptyMap()
        }
    }

    private fun loadInfo() {
        viewModelScope.launch {
            try {
                _info.value = fastbootRepository.getDeviceInfo()
            } catch (e: Exception) {
                _result.emit("获取设备信息失败: ${e.message}")
            }
        }
    }

    fun reboot() {
        viewModelScope.launch {
            val r = fastbootRepository.reboot()
            _result.emit(r)
            disconnect()
        }
    }

    fun rebootRecovery() {
        viewModelScope.launch {
            val r = fastbootRepository.rebootRecovery()
            _result.emit(r)
            disconnect()
        }
    }

    fun rebootBootloader() {
        viewModelScope.launch {
            val r = fastbootRepository.rebootBootloader()
            _result.emit(r)
            kotlinx.coroutines.delay(2000)
            scanDevices()
        }
    }

    fun flash(partition: String, data: ByteArray) {
        viewModelScope.launch {
            _flashProgress.value = 0
            val r = fastbootRepository.flash(partition, data) { progress ->
                _flashProgress.value = progress
            }
            _result.emit(r)
            _flashProgress.value = -1
        }
    }

    fun erase(partition: String) {
        viewModelScope.launch {
            val r = fastbootRepository.erase(partition)
            _result.emit(r)
        }
    }

    fun oem(command: String) {
        viewModelScope.launch {
            val r = fastbootRepository.oem(command)
            _result.emit(r)
        }
    }

    fun loadVarAll() {
        viewModelScope.launch {
            _info.value = fastbootRepository.getVarAll()
        }
    }

    fun flashingUnlock() {
        viewModelScope.launch {
            val r = fastbootRepository.flashingUnlock()
            _result.emit(r)
        }
    }

    fun flashingLock() {
        viewModelScope.launch {
            val r = fastbootRepository.flashingLock()
            _result.emit(r)
        }
    }

    fun boot(data: ByteArray) {
        viewModelScope.launch {
            _flashProgress.value = 0
            val r = fastbootRepository.boot(data) { progress ->
                _flashProgress.value = progress
            }
            _result.emit(r)
            _flashProgress.value = -1
        }
    }

    fun stage(data: ByteArray) {
        viewModelScope.launch {
            _flashProgress.value = 0
            val r = fastbootRepository.stage(data) { progress ->
                _flashProgress.value = progress
            }
            _result.emit(r)
            _flashProgress.value = -1
        }
    }

    fun fetch() {
        viewModelScope.launch {
            val (message, _) = fastbootRepository.fetch()
            _result.emit(message)
        }
    }
}
