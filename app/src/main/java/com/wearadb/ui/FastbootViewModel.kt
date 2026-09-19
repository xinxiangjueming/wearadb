package com.wearadb.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.R
import com.wearadb.fastboot.FastbootConnectionState
import com.wearadb.fastboot.FastbootDevice
import com.wearadb.fastboot.FastbootRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Fastboot operations (USB bootloader mode).
 */
@HiltViewModel
class FastbootViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
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

    /** 刷入成功的结构化信号：界面据此弹重启确认，不再靠文案关键字判断。 */
    private val _flashDone = MutableSharedFlow<Unit>()
    val flashDone: SharedFlow<Unit> = _flashDone.asSharedFlow()

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
                    _result.emit(appContext.getString(R.string.fb_connect_failed_detail))
                }
            } catch (e: Exception) {
                _result.emit(appContext.getString(R.string.fb_connect_exception, e.message ?: ""))
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
                _result.emit(appContext.getString(R.string.fb_getvar_failed, e.message ?: ""))
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
            val (ok, message) = fastbootRepository.flash(partition, data) { progress ->
                _flashProgress.value = progress
            }
            _result.emit(message)
            if (ok) _flashDone.emit(Unit)
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
