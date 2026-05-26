package com.wearadb.fastboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Fastboot 设备信息
 */
data class FastbootDevice(
    val usbDevice: UsbDevice,
    val serialNumber: String,
    val productName: String = ""
) {
    val displayName: String
        get() = if (productName.isNotEmpty()) "$productName ($serialNumber)" else serialNumber
}

/**
 * Fastboot 连接状态
 */
enum class FastbootConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

/**
 * Fastboot 协议底层实现。
 * 通过 Android USB Host API 与 fastboot 设备通信。
 *
 * Fastboot 协议极简：
 * - 命令: ASCII 字符串发送到 bulk OUT
 * - 响应: 4 字节标签 (OKAY/FAIL/DATA/INFO) + 数据
 */
class FastbootManager(private val context: Context) {

    companion object {
        private const val TAG = "FastbootManager"
        private const val ACTION_USB_PERMISSION = "com.wearadb.USB_PERMISSION"
        private const val FASTBOOT_INTERFACE_CLASS = 255       // 0xFF - Vendor-specific
        private const val FASTBOOT_INTERFACE_SUBCLASS = 66     // 0x42
        private const val FASTBOOT_INTERFACE_PROTOCOL = 3      // 0x03
        private const val TIMEOUT_MS = 10000
        private const val FLASH_CHUNK_SIZE = 1024 * 1024       // 1MB per chunk

        // Fastboot response tags
        private val TAG_OKAY = "OKAY".toByteArray()
        private val TAG_FAIL = "FAIL".toByteArray()
        private val TAG_DATA = "DATA".toByteArray()
        private val TAG_INFO = "INFO".toByteArray()
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var fastbootInterface: UsbInterface? = null
    private var endpointOut: UsbEndpoint? = null
    private var endpointIn: UsbEndpoint? = null

    private var permissionGranted = false
    private var pendingDevice: UsbDevice? = null

    // USB 权限回调
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        permissionGranted = true
                        Log.d(TAG, "USB permission granted for ${device.deviceName}")
                    } else {
                        Log.w(TAG, "USB permission denied")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
    }

    // ── 设备扫描 ──

    /**
     * 扫描所有处于 fastboot 模式的 USB 设备。
     */
    fun scanDevices(): List<FastbootDevice> {
        val devices = mutableListOf<FastbootDevice>()
        for (device in usbManager.deviceList.values) {
            if (isFastbootDevice(device)) {
                val serial = device.serialNumber ?: device.deviceName
                val product = try { device.productName ?: "" } catch (_: Exception) { "" }
                devices.add(FastbootDevice(device, serial, product))
            }
        }
        Log.d(TAG, "scanDevices: found ${devices.size} fastboot device(s)")
        return devices
    }

    /**
     * 检测设备是否处于 fastboot 模式。
     * 匹配 interface: class=0xFF, subclass=0x42, protocol=0x03
     */
    private fun isFastbootDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == FASTBOOT_INTERFACE_CLASS &&
                iface.interfaceSubclass == FASTBOOT_INTERFACE_SUBCLASS &&
                iface.interfaceProtocol == FASTBOOT_INTERFACE_PROTOCOL
            ) {
                return true
            }
        }
        return false
    }


    // ── 连接 ──

    /**
     * 连接到 fastboot 设备。
     * 需要 USB 权限，首次会弹出授权对话框。
     */
    suspend fun connect(device: FastbootDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to ${device.serialNumber}...")
            disconnect()

            val usbDevice = device.usbDevice

            // 检查/请求 USB 权限
            if (!usbManager.hasPermission(usbDevice)) {
                Log.d(TAG, "Requesting USB permission...")
                permissionGranted = false
                pendingDevice = usbDevice
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION), flags
                )
                usbManager.requestPermission(usbDevice, permissionIntent)

                // 等待权限授予（最多 10 秒）
                val waitStart = System.currentTimeMillis()
                while (!permissionGranted && System.currentTimeMillis() - waitStart < 10000) {
                    delay(100)
                }
                if (!permissionGranted) {
                    Log.w(TAG, "USB permission not granted within timeout")
                    return@withContext false
                }
            }

            // 打开设备
            val conn = usbManager.openDevice(usbDevice)
            if (conn == null) {
                Log.e(TAG, "Failed to open USB device")
                return@withContext false
            }

            // 找到 fastboot interface 和 endpoints
            var foundInterface: UsbInterface? = null
            var outEp: UsbEndpoint? = null
            var inEp: UsbEndpoint? = null

            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                if (iface.interfaceClass == FASTBOOT_INTERFACE_CLASS &&
                    iface.interfaceSubclass == FASTBOOT_INTERFACE_SUBCLASS &&
                    iface.interfaceProtocol == FASTBOOT_INTERFACE_PROTOCOL
                ) {
                    foundInterface = iface
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                            else if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                        }
                    }
                    break
                }
            }

            if (foundInterface == null || outEp == null || inEp == null) {
                Log.e(TAG, "Fastboot interface or endpoints not found")
                conn.close()
                return@withContext false
            }

            if (!conn.claimInterface(foundInterface, true)) {
                Log.e(TAG, "Failed to claim fastboot interface")
                conn.close()
                return@withContext false
            }

            connection = conn
            fastbootInterface = foundInterface
            endpointOut = outEp
            endpointIn = inEp

            Log.d(TAG, "Connected to fastboot device: ${device.serialNumber}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect exception: ${e.message}", e)
            false
        }
    }

    fun disconnect() {
        try {
            fastbootInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        fastbootInterface = null
        endpointOut = null
        endpointIn = null
        Log.d(TAG, "Disconnected")
    }

    fun isConnected(): Boolean = connection != null

    // ── Fastboot 命令 ──

    /**
     * 发送 fastboot 命令并读取响应。
     * 返回 FastbootResponse。
     */
    private fun executeCommand(command: String): FastbootResponse {
        val conn = connection ?: return FastbootResponse.Error("Not connected")
        val outEp = endpointOut ?: return FastbootResponse.Error("No OUT endpoint")
        val inEp = endpointIn ?: return FastbootResponse.Error("No IN endpoint")

        Log.d(TAG, ">>> $command")

        // 发送命令
        val cmdBytes = command.toByteArray()
        val sent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        if (sent < 0) {
            return FastbootResponse.Error("Failed to send command")
        }

        // 读取响应
        return readResponse(conn, inEp)
    }

    /**
     * 读取 fastboot 响应。
     * 响应格式: 4字节标签 + 可变长度数据
     */
    private fun readResponse(conn: UsbDeviceConnection, inEp: UsbEndpoint): FastbootResponse {
        val headerBuf = ByteArray(4)
        val infoMessages = mutableListOf<String>()

        while (true) {
            val read = conn.bulkTransfer(inEp, headerBuf, 4, TIMEOUT_MS)
            if (read < 4) {
                return FastbootResponse.Error("Failed to read response header (read=$read)")
            }

            val tag = String(headerBuf, 0, 4)
            Log.d(TAG, "<<< $tag")

            when (tag) {
                "OKAY" -> {
                    // OKAY 可能跟随数据（getvar 返回值在 OKAY 后面的 4 字节 hex 长度 + 数据）
                    // 但大多数情况 OKAY 就是空的，尝试读取后续数据
                    val extra = readAvailableData(conn, inEp)
                    return FastbootResponse.Okay(extra.trim(), infoMessages)
                }
                "FAIL" -> {
                    val errorMsg = readAvailableData(conn, inEp)
                    return FastbootResponse.Error("FAIL: $errorMsg", infoMessages)
                }
                "DATA" -> {
                    // DATA<4字节hex长度>
                    val sizeBuf = ByteArray(4)
                    val sizeRead = conn.bulkTransfer(inEp, sizeBuf, 4, TIMEOUT_MS)
                    if (sizeRead < 4) {
                        return FastbootResponse.Error("Failed to read DATA size")
                    }
                    val dataSize = String(sizeBuf, 0, 4).toInt(16)
                    return FastbootResponse.DataReady(dataSize, infoMessages)
                }
                "INFO" -> {
                    val infoText = readAvailableData(conn, inEp)
                    infoMessages.add(infoText)
                    Log.d(TAG, "INFO: $infoText")
                    // 继续读取下一条响应
                }
                else -> {
                    return FastbootResponse.Error("Unknown response tag: $tag")
                }
            }
        }
    }

    /**
     * 读取可用数据（直到没有更多数据或超时）。
     */
    private fun readAvailableData(conn: UsbDeviceConnection, inEp: UsbEndpoint): String {
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        // 先读一批
        val n = conn.bulkTransfer(inEp, buf, buf.size, 2000)
        if (n > 0) {
            sb.append(String(buf, 0, n))
        }
        // 尝试读更多（短超时）
        while (true) {
            val more = conn.bulkTransfer(inEp, buf, buf.size, 200)
            if (more <= 0) break
            sb.append(String(buf, 0, more))
        }
        return sb.toString()
    }

    // ── 公开命令 ──

    /**
     * 获取 fastboot 变量。
     * getvar:version, getvar:product, getvar:serialno, getvar:all 等
     */
    fun getVar(key: String): String {
        val resp = executeCommand("getvar:$key")
        return when (resp) {
            is FastbootResponse.Okay -> resp.data
            is FastbootResponse.Error -> resp.message
            else -> ""
        }
    }

    /**
     * 重启到系统
     */
    fun reboot(): String {
        val resp = executeCommand("reboot")
        return when (resp) {
            is FastbootResponse.Okay -> "重启中..."
            is FastbootResponse.Error -> resp.message
            else -> "已发送重启命令"
        }
    }

    /**
     * 重启到 recovery
     */
    fun rebootRecovery(): String {
        val resp = executeCommand("reboot-recovery")
        return when (resp) {
            is FastbootResponse.Okay -> "重启到 Recovery..."
            is FastbootResponse.Error -> resp.message
            else -> "已发送重启命令"
        }
    }

    /**
     * 重启回 bootloader
     */
    fun rebootBootloader(): String {
        val resp = executeCommand("reboot-bootloader")
        return when (resp) {
            is FastbootResponse.Okay -> "重启到 Bootloader..."
            is FastbootResponse.Error -> resp.message
            else -> "已发送重启命令"
        }
    }

    /**
     * 擦除分区
     */
    fun erase(partition: String): String {
        val resp = executeCommand("erase:$partition")
        return when (resp) {
            is FastbootResponse.Okay -> "擦除 $partition 成功"
            is FastbootResponse.Error -> "擦除失败: ${resp.message}"
            else -> "擦除完成"
        }
    }

    /**
     * 执行 OEM 命令
     */
    fun oem(command: String): String {
        val resp = executeCommand("oem $command")
        return when (resp) {
            is FastbootResponse.Okay -> resp.data.ifEmpty { "OEM 命令执行成功" }
            is FastbootResponse.Error -> "OEM 命令失败: ${resp.message}"
            else -> "OEM 命令已执行"
        }
    }

    /**
     * 刷入分区。
     * 协议流程: download <size> → DATA → 发送数据 → OKAY
     * @param progressCallback 进度回调 (0-100)
     */
    fun flash(partition: String, data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String {
        val conn = connection ?: return "未连接"
        val outEp = endpointOut ?: return "无 OUT endpoint"
        val inEp = endpointIn ?: return "无 IN endpoint"

        Log.d(TAG, "Flashing $partition: ${data.size} bytes")

        // 1. 发送 download 命令
        val sizeHex = String.format("%08x", data.size)
        val downloadCmd = "download:$sizeHex"
        val cmdBytes = downloadCmd.toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)

        // 2. 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        if (resp !is FastbootResponse.DataReady) {
            return "下载请求被拒绝: ${(resp as? FastbootResponse.Error)?.message ?: "未知错误"}"
        }

        // 3. 分块发送数据
        var offset = 0
        while (offset < data.size) {
            val len = minOf(FLASH_CHUNK_SIZE, data.size - offset)
            val sent = conn.bulkTransfer(outEp, data, offset, len, TIMEOUT_MS * 3)
            if (sent < 0) {
                return "数据传输失败 (offset=$offset)"
            }
            offset += sent
            val progress = (offset * 100 / data.size)
            progressCallback?.invoke(progress)
            Log.d(TAG, "Flash progress: $progress% ($offset/${data.size})")
        }

        // 4. 读取最终 OKAY 响应
        val finalResp = readResponse(conn, inEp)
        return when (finalResp) {
            is FastbootResponse.Okay -> {
                Log.d(TAG, "Flash $partition completed")
                progressCallback?.invoke(100)
                "刷入 $partition 成功 (${data.size / 1024}KB)"
            }
            is FastbootResponse.Error -> "刷入失败: ${finalResp.message}"
            else -> "刷入完成"
        }
    }

    /**
     * 获取设备详细信息
     */
    fun getDeviceInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        val keys = listOf("version", "product", "serialno", "slot-count", "variant", "battery-voltage")
        for (key in keys) {
            val value = getVar(key)
            if (value.isNotEmpty()) {
                info[key] = value
            }
        }
        return info
    }

    /**
     * 获取所有 fastboot 变量（getvar:all）。
     * getvar:all 返回 INFO 行格式的 key:value 对。
     */
    fun getVarAll(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        val conn = connection ?: return info
        val outEp = endpointOut ?: return info
        val inEp = endpointIn ?: return info

        Log.d(TAG, ">>> getvar:all")
        val cmdBytes = "getvar:all".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)

        // 读取所有 INFO 行，最后一行 OKAY
        val headerBuf = ByteArray(4)
        while (true) {
            val read = conn.bulkTransfer(inEp, headerBuf, 4, TIMEOUT_MS)
            if (read < 4) break
            val tag = String(headerBuf, 0, 4)
            when (tag) {
                "OKAY" -> {
                    readAvailableData(conn, inEp) // 消费剩余数据
                    break
                }
                "INFO" -> {
                    val text = readAvailableData(conn, inEp)
                    val colonIdx = text.indexOf(':')
                    if (colonIdx > 0) {
                        val key = text.substring(0, colonIdx).trim()
                        val value = text.substring(colonIdx + 1).trim()
                        if (key.isNotEmpty()) info[key] = value
                    }
                    Log.d(TAG, "getvar:all INFO: $text")
                }
                "FAIL" -> {
                    readAvailableData(conn, inEp)
                    break
                }
                else -> break
            }
        }
        return info
    }

    /**
     * 解锁 bootloader。
     * fastboot flashing unlock
     * 注意：部分设备会清除数据，部分设备需要确认。
     */
    fun flashingUnlock(): String {
        val resp = executeCommand("flashing unlock")
        return when (resp) {
            is FastbootResponse.Okay -> "Bootloader 解锁成功"
            is FastbootResponse.Error -> "解锁失败: ${resp.message}"
            else -> "解锁命令已发送（设备可能需要确认）"
        }
    }

    /**
     * 锁定 bootloader。
     * fastboot flashing lock
     */
    fun flashingLock(): String {
        val resp = executeCommand("flashing lock")
        return when (resp) {
            is FastbootResponse.Okay -> "Bootloader 已锁定"
            is FastbootResponse.Error -> "锁定失败: ${resp.message}"
            else -> "锁定命令已发送"
        }
    }

    /**
     * 临时启动镜像（不刷入）。
     * fastboot boot <image>
     * 协议: 发送 "boot" 命令 → DATA → 发送镜像数据 → 设备临时启动
     * @param progressCallback 进度回调 (0-100)
     */
    fun boot(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String {
        val conn = connection ?: return "未连接"
        val outEp = endpointOut ?: return "无 OUT endpoint"
        val inEp = endpointIn ?: return "无 IN endpoint"

        Log.d(TAG, "boot: ${data.size} bytes")

        // 1. 发送 boot 命令（不带 size，由协议自动处理）
        val cmdBytes = "boot".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)

        // 2. 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        if (resp !is FastbootResponse.DataReady) {
            return "boot 请求被拒绝: ${(resp as? FastbootResponse.Error)?.message ?: "未知错误"}"
        }

        // 3. 分块发送镜像数据
        var offset = 0
        while (offset < data.size) {
            val len = minOf(FLASH_CHUNK_SIZE, data.size - offset)
            val sent = conn.bulkTransfer(outEp, data, offset, len, TIMEOUT_MS * 3)
            if (sent < 0) return "数据传输失败 (offset=$offset)"
            offset += sent
            progressCallback?.invoke(offset * 100 / data.size)
        }

        // 4. 读取最终响应
        val finalResp = readResponse(conn, inEp)
        return when (finalResp) {
            is FastbootResponse.Okay -> {
                progressCallback?.invoke(100)
                "临时启动成功，设备正在重启..."
            }
            is FastbootResponse.Error -> "boot 失败: ${finalResp.message}"
            else -> "boot 命令已发送"
        }
    }

    /**
     * 上传数据到设备（stage）。
     * fastboot stage <size> → DATA → 发送数据
     * 数据暂存到设备内存，配合 oem 命令使用。
     * @param progressCallback 进度回调 (0-100)
     */
    fun stage(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String {
        val conn = connection ?: return "未连接"
        val outEp = endpointOut ?: return "无 OUT endpoint"
        val inEp = endpointIn ?: return "无 IN endpoint"

        Log.d(TAG, "stage: ${data.size} bytes")

        // 1. 发送 stage 命令
        val sizeHex = String.format("%08x", data.size)
        val cmdBytes = "stage:$sizeHex".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)

        // 2. 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        if (resp !is FastbootResponse.DataReady) {
            return "stage 请求被拒绝: ${(resp as? FastbootResponse.Error)?.message ?: "未知错误"}"
        }

        // 3. 分块发送数据
        var offset = 0
        while (offset < data.size) {
            val len = minOf(FLASH_CHUNK_SIZE, data.size - offset)
            val sent = conn.bulkTransfer(outEp, data, offset, len, TIMEOUT_MS * 3)
            if (sent < 0) return "数据传输失败 (offset=$offset)"
            offset += sent
            progressCallback?.invoke(offset * 100 / data.size)
        }

        // 4. 读取最终响应
        val finalResp = readResponse(conn, inEp)
        return when (finalResp) {
            is FastbootResponse.Okay -> {
                progressCallback?.invoke(100)
                "数据已上传到设备 (${data.size / 1024}KB)"
            }
            is FastbootResponse.Error -> "stage 失败: ${finalResp.message}"
            else -> "stage 完成"
        }
    }

    /**
     * 从设备下载数据（fetch）。
     * fastboot fetch → 设备返回 DATA → 读取数据
     * @return Pair(resultMessage, data) 或 null
     */
    fun fetch(): Pair<String, ByteArray?> {
        val conn = connection ?: return "未连接" to null
        val outEp = endpointOut ?: return "无 OUT endpoint" to null
        val inEp = endpointIn ?: return "无 IN endpoint" to null

        Log.d(TAG, ">>> fetch")
        val cmdBytes = "fetch".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)

        // 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        if (resp !is FastbootResponse.DataReady) {
            val msg = (resp as? FastbootResponse.Error)?.message ?: "fetch 请求被拒绝"
            return msg to null
        }

        // 读取数据
        val dataSize = resp.dataSize
        Log.d(TAG, "fetch: expecting $dataSize bytes")
        val buffer = ByteArray(dataSize)
        var totalRead = 0
        while (totalRead < dataSize) {
            val toRead = minOf(FLASH_CHUNK_SIZE, dataSize - totalRead)
            val n = conn.bulkTransfer(inEp, buffer, totalRead, toRead, TIMEOUT_MS * 3)
            if (n < 0) return "数据接收失败 (offset=$totalRead)" to null
            totalRead += n
        }

        // 读取最终 OKAY
        readResponse(conn, inEp)
        Log.d(TAG, "fetch: received $totalRead bytes")
        return "数据下载成功 (${totalRead / 1024}KB)" to buffer
    }

    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
    }
}

/**
 * Fastboot 响应类型
 */
sealed class FastbootResponse {
    /** 命令成功，data 为 OKAY 后面附带的数据 */
    data class Okay(val data: String, val infoMessages: List<String> = emptyList()) : FastbootResponse()

    /** 命令失败 */
    data class Error(val message: String, val infoMessages: List<String> = emptyList()) : FastbootResponse()

    /** 准备接收数据，dataSize 为要接收的字节数 */
    data class DataReady(val dataSize: Int, val infoMessages: List<String> = emptyList()) : FastbootResponse()
}
