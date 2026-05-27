package com.wearadb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearadb.adb.UsbAdbConnectionState
import com.wearadb.adb.UsbAdbDeviceInfo
import com.wearadb.adb.UsbAdbRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for wired USB ADB operations.
 */
@HiltViewModel
class UsbAdbViewModel @Inject constructor(
    private val usbAdbRepository: UsbAdbRepository
) : ViewModel() {

    val connectionState: StateFlow<UsbAdbConnectionState> = usbAdbRepository.connectionState
    val connectedDevice: StateFlow<UsbAdbDeviceInfo?> = usbAdbRepository.connectedDevice
    val connectLog: StateFlow<String> = usbAdbRepository.connectLog

    private val _devices = MutableStateFlow<List<UsbAdbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UsbAdbDeviceInfo>> = _devices.asStateFlow()

    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput.asStateFlow()

    fun scanDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            _devices.value = usbAdbRepository.scanDevices()
        }
    }

    fun connect(deviceInfo: UsbAdbDeviceInfo) {
        viewModelScope.launch {
            usbAdbRepository.connect(deviceInfo)
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            usbAdbRepository.disconnect()
            _shellOutput.value = ""
        }
    }

    fun executeCommand(command: String) {
        viewModelScope.launch {
            _shellOutput.value = "执行中..."
            _shellOutput.value = usbAdbRepository.executeCommand(command)
        }
    }
}
