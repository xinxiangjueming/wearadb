package com.wearadb.fastboot

import android.content.Context
import com.wearadb.log.WearAdbLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fastboot 仓库层。
 * 封装 FastbootManager，提供 suspend 函数给 ViewModel。
 */
@Singleton
class FastbootRepository @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val _connectLog = MutableStateFlow("")
    val connectLog: StateFlow<String> = _connectLog.asStateFlow()
    private val logLines = mutableListOf<String>()

    private val manager = FastbootManager(appContext) { msg ->
        synchronized(logLines) {
            logLines.add(msg)
            _connectLog.value = logLines.joinToString("\n")
        }
    }

    private val _connectionState = MutableStateFlow(FastbootConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FastbootConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<FastbootDevice?>(null)
    val connectedDevice: StateFlow<FastbootDevice?> = _connectedDevice.asStateFlow()

    // ── 设备扫描 ──

    fun scanDevices(): List<FastbootDevice> = manager.scanDevices()

    // ── 连接 ──

    suspend fun connect(device: FastbootDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            WearAdbLogger.i("Fastboot", "Fastboot连接: ${device.displayName}")
            synchronized(logLines) { logLines.clear(); _connectLog.value = "" }
            _connectionState.value = FastbootConnectionState.CONNECTING
            val success = manager.connect(device)
            if (success) {
                WearAdbLogger.i("Fastboot", "Fastboot连接成功: ${device.displayName}")
                _connectionState.value = FastbootConnectionState.CONNECTED
                _connectedDevice.value = device
            } else {
                WearAdbLogger.w("Fastboot", "Fastboot连接失败: ${device.displayName}")
                _connectionState.value = FastbootConnectionState.ERROR
                _connectedDevice.value = null
            }
            success
        } catch (e: Exception) {
            WearAdbLogger.e("Fastboot", "Fastboot连接异常: ${device.displayName} - ${e.message}", e)
            _connectionState.value = FastbootConnectionState.ERROR
            _connectedDevice.value = null
            false
        }
    }

    fun disconnect() {
        WearAdbLogger.i("Fastboot", "Fastboot断开连接")
        manager.disconnect()
        _connectionState.value = FastbootConnectionState.DISCONNECTED
        _connectedDevice.value = null
    }

    fun isConnected(): Boolean = manager.isConnected()

    // ── 信息查询 ──

    suspend fun getVar(key: String): String = withContext(Dispatchers.IO) {
        manager.getVar(key)
    }

    suspend fun getDeviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        manager.getDeviceInfo()
    }

    // ── 重启命令 ──

    suspend fun reboot(): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot重启")
        manager.reboot()
    }

    suspend fun rebootRecovery(): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot重启到Recovery")
        val result = manager.rebootRecovery()
        // 重启后连接会断开
        disconnect()
        result
    }

    suspend fun rebootBootloader(): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot重启到Bootloader")
        val result = manager.rebootBootloader()
        // 重启到 bootloader 后需要重新扫描
        disconnect()
        result
    }

    // ── 分区操作 ──

    suspend fun erase(partition: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot擦除分区: $partition")
        manager.erase(partition)
    }

    suspend fun flash(partition: String, data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            WearAdbLogger.i("Fastboot", "Fastboot刷写分区: $partition, size=${data.size}")
            val result = manager.flash(partition, data, progressCallback)
            WearAdbLogger.i("Fastboot", "Fastboot刷写结果: $partition - $result")
            result
        }

    suspend fun oem(command: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot OEM: $command")
        manager.oem(command)
    }

    suspend fun getVarAll(): Map<String, String> = withContext(Dispatchers.IO) {
        manager.getVarAll()
    }

    suspend fun flashingUnlock(): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot解锁Bootloader")
        manager.flashingUnlock()
    }

    suspend fun flashingLock(): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("Fastboot", "Fastboot锁定Bootloader")
        manager.flashingLock()
    }

    suspend fun boot(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            WearAdbLogger.i("Fastboot", "Fastboot启动镜像: size=${data.size}")
            manager.boot(data, progressCallback)
        }

    suspend fun stage(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            WearAdbLogger.i("Fastboot", "Fastboot暂存镜像: size=${data.size}")
            manager.stage(data, progressCallback)
        }

    suspend fun fetch(): Pair<String, ByteArray?> = withContext(Dispatchers.IO) {
        manager.fetch()
    }

    // ── 清理 ──

    fun destroy() {
        disconnect()
        manager.destroy()
    }
}
