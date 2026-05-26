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
class FastbootManager(
    private val context: Context,
    private val logCallback: ((String) -> Unit)? = null
) {

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

        /** fastboot 变量名 → 中文显示名 */
        private val LABELS = mapOf(
            "product" to "设备代号",
            "serialno" to "序列号",
            "variant" to "存储类型",
            "slot-count" to "分区槽数",
            "current-slot" to "当前槽位",
            "secure" to "安全启动",
            "unlocked" to "BL已解锁",
            "battery-voltage" to "电池电压(mV)",
            "battery-soc-ok" to "电池电量充足",
            "hw-revision" to "硬件版本",
            "erase-block-size" to "擦除块大小",
            "logical-block-size" to "逻辑块大小",
            "max-download-size" to "最大下载大小",
            "kernel" to "内核类型",
            "has-slot:boot" to "Boot分区A/B",
            "has-slot:modem" to "Modem分区A/B",
            "has-slot:system" to "System分区A/B",
            "version-bootloader" to "Bootloader版本",
            "version-baseband" to "基带版本",
            "dpstatus" to "DP状态",
            "socid" to "SoC ID",
            "snapshot-update-status" to "快照更新状态",
            "is-userspace" to "用户空间模式"
        )

        private fun label(key: String) = LABELS[key] ?: key
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var fastbootInterface: UsbInterface? = null
    private var endpointOut: UsbEndpoint? = null
    private var endpointIn: UsbEndpoint? = null

    // 线程安全：确保同一时间只有一个操作在使用 USB 连接
    private val usbLock = Any()
    @Volatile private var closed = false  // disconnect 时置 true，中止进行中的操作

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
                // serialNumber / productName 在部分设备和 Android 版本上会抛 SecurityException
                val serial = try { device.serialNumber ?: device.deviceName } catch (_: Exception) { device.deviceName }
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
     *
     * 权限获取策略：
     * 1. 先尝试直接 openDevice（已授权的设备直接成功）
     * 2. 请求权限 → 轮询 openDevice（不依赖 BroadcastReceiver，兼容性更好）
     */
    suspend fun connect(device: FastbootDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            log("连接 ${device.serialNumber}...")
            disconnect()
            closed = false  // 重置，允许新连接

            val usbDevice = device.usbDevice

            // 打印设备所有接口信息
            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                log("  接口[$i]: class=0x${iface.interfaceClass.toString(16)} " +
                    "sub=0x${iface.interfaceSubclass.toString(16)} " +
                    "proto=0x${iface.interfaceProtocol.toString(16)} " +
                    "端点=${iface.endpointCount}")
            }

            // 1. 尝试直接打开
            val hasPerm = usbManager.hasPermission(usbDevice)
            log("打开设备: hasPermission=$hasPerm")
            var conn = tryOpenDevice(usbDevice)

            // 2. 没权限则请求并轮询
            if (conn == null) {
                log("需要USB权限, 正在请求...")
                permissionGranted = false
                pendingDevice = usbDevice
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION), flags
                )
                usbManager.requestPermission(usbDevice, permissionIntent)

                val waitStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - waitStart < 20000) {
                    delay(500)
                    conn = tryOpenDevice(usbDevice)
                    if (conn != null) {
                        log("权限获取成功 (${System.currentTimeMillis() - waitStart}ms)")
                        break
                    }
                    if (permissionGranted) {
                        delay(300)
                        conn = tryOpenDevice(usbDevice)
                        if (conn != null) {
                            log("权限获取成功(广播)")
                            break
                        }
                    }
                }

                if (conn == null) {
                    logError("权限超时, hasPermission=${usbManager.hasPermission(usbDevice)}")
                    return@withContext false
                }
            } else {
                log("设备已授权, 直接打开成功")
            }

            // 3. 查找 fastboot 接口
            log("查找fastboot接口...")
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
                    log("找到标准fastboot接口 (index $i)")
                    break
                }
            }

            if (foundInterface == null) {
                log("标准匹配未命中, 宽松匹配中...")
                for (i in 0 until usbDevice.interfaceCount) {
                    val iface = usbDevice.getInterface(i)
                    if (iface.interfaceClass == FASTBOOT_INTERFACE_CLASS && iface.endpointCount >= 2) {
                        var tmpOut: UsbEndpoint? = null
                        var tmpIn: UsbEndpoint? = null
                        for (j in 0 until iface.endpointCount) {
                            val ep = iface.getEndpoint(j)
                            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                if (ep.direction == UsbConstants.USB_DIR_OUT) tmpOut = ep
                                else if (ep.direction == UsbConstants.USB_DIR_IN) tmpIn = ep
                            }
                        }
                        if (tmpOut != null && tmpIn != null) {
                            foundInterface = iface
                            outEp = tmpOut
                            inEp = tmpIn
                            log("宽松匹配成功 (index $i, sub=0x${iface.interfaceSubclass.toString(16)})")
                            break
                        }
                    }
                }
            }

            if (foundInterface == null || outEp == null || inEp == null) {
                logError("未找到可用的USB接口(需要bulk IN+OUT端点)")
                conn.close()
                return@withContext false
            }

            // 4. 先释放再 claim（确保内核驱动完全脱离）
            try { conn.releaseInterface(foundInterface) } catch (_: Exception) {}
            if (!conn.claimInterface(foundInterface, true)) {
                logError("claimInterface失败")
                conn.close()
                return@withContext false
            }

            delay(500) // 等内核驱动完全释放

            synchronized(usbLock) {
                connection = conn
                fastbootInterface = foundInterface
                endpointOut = outEp
                endpointIn = inEp
            }

            log("接口已获取")
            log("  iface: id=${foundInterface.id} alt=${foundInterface.alternateSetting}")
            log("  OUT: 0x${outEp.address.toString(16)} maxPkt=${outEp.maxPacketSize}")
            log("  IN:  0x${inEp.address.toString(16)} maxPkt=${inEp.maxPacketSize}")

            // 5. 尝试激活接口（兼容 TWRP 等非标准实现）
            log("激活USB接口...")
            // 5a. SET_INTERFACE — 某些设备需要显式激活 alternate setting
            try {
                val r1 = conn.controlTransfer(0x01, 0x0B,
                    foundInterface.alternateSetting, foundInterface.id,
                    null, 0, 1000)
                log("  SET_INTERFACE=$r1")
            } catch (_: Exception) {}
            // 5b. SET_CONFIGURATION
            try {
                val r2 = conn.controlTransfer(0x00, 0x09, 0x0001, 0x0000, null, 0, 1000)
                log("  SET_CONFIGURATION=$r2")
            } catch (_: Exception) {}
            // 5c. 清除 halt
            clearEndpointHalt(conn, outEp)
            clearEndpointHalt(conn, inEp)
            delay(300)

            // 7. USB 设备 reset（唤醒设备的收发状态）
            log("USB设备reset...")
            try {
                // USB_RESET = 4 (ioctl)
                // 通过 controlTransfer GET_STATUS 模拟设备唤醒
                val statusBuf = ByteArray(4)
                val getStatusResult = conn.controlTransfer(
                    0x80,  // bmRequestType: device, device-to-host
                    0x00,  // bRequest: GET_STATUS
                    0x0000, 0x0000,
                    statusBuf, 4, 1000
                )
                log("  GET_STATUS result=$getStatusResult, data=${statusBuf.joinToString { "%02x".format(it) }}")
            } catch (e: Exception) {
                log("  GET_STATUS 异常: ${e.message}")
            }
            Thread.sleep(300)

            // 8. 清除 halt（reset 后再清一次）
            clearEndpointHalt(conn, outEp)
            clearEndpointHalt(conn, inEp)
            Thread.sleep(200)

            // 9. 消费 IN 端点上可能残留的数据
            log("清空IN端点缓冲区...")
            val drainBuf = ByteArray(512)
            while (true) {
                val n = conn.bulkTransfer(inEp, drainBuf, 512, 100)
                if (n <= 0) break
                log("  清空了 $n 字节残留数据")
            }

            // 10. 验证通信（失败则重新扫描设备后重试）
            log("测试通信: getvar:product...")
            var testResp = trySendTestCommand(conn, outEp, inEp)
            log("getvar:product → $testResp")

            if (testResp.startsWith("失败") || testResp.startsWith("异常")) {
                logError("通信失败: $testResp")
                logError("请在电脑上测试: fastboot getvar product")
                logError("如果电脑OK，可能是手机OTG/USB口问题")
                disconnect()
                return@withContext false
            }

            log("连接成功!")
            true
        } catch (e: Exception) {
            logError("连接异常: ${e.message}")
            false
        }
    }

    fun disconnect() {
        closed = true  // 通知所有进行中的操作停止
        // 不等 usbLock，直接清理引用（避免主线程 ANR）
        // IO 线程看到 closed=true 后会自行退出
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

    fun isConnected(): Boolean = synchronized(usbLock) { connection != null }

    /** 同时写入 Logcat 和 logCallback（显示在 UI 上） */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback?.invoke(msg)
    }

    private fun logError(msg: String) {
        Log.e(TAG, msg)
        logCallback?.invoke("[错误] $msg")
    }

    /** 直接发送测试命令（使用 UsbRequest 异步读取） */
    private fun trySendTestCommand(
        conn: UsbDeviceConnection, outEp: UsbEndpoint, inEp: UsbEndpoint
    ): String {
        return try {
            // 1. 发送命令
            val cmd = "getvar:product".toByteArray()
            val sent = conn.bulkTransfer(outEp, cmd, cmd.size, TIMEOUT_MS)
            if (sent < 0) return "失败: 发送失败(sent=$sent)"
            log("  test: 已发送 ${cmd.size} 字节")

            // 2. 读取响应（不加延迟，立即读取）
            val readBuf = ByteArray(4096)
            val bytesRead = usbRead(conn, inEp, readBuf, TIMEOUT_MS)
            log("  test: usbRead返回 $bytesRead")
            if (bytesRead < 4) return "失败: 响应太短 ($bytesRead 字节)"

            val tag = String(readBuf, 0, 4)
            log("  test: 收到标签 '$tag', 共 $bytesRead 字节")

            return when (tag) {
                "OKAY" -> {
                    val data = if (bytesRead > 4) String(readBuf, 4, bytesRead - 4).trim() else ""
                    "OK: $data"
                }
                "FAIL" -> {
                    val data = if (bytesRead > 4) String(readBuf, 4, bytesRead - 4).trim() else ""
                    "失败: FAIL $data"
                }
                "INFO" -> {
                    val info = if (bytesRead > 4) String(readBuf, 4, bytesRead - 4).trim() else ""
                    "OK: (INFO: $info)"
                }
                else -> "失败: 未知标签 '$tag'"
            }
        } catch (e: Exception) {
            "异常: ${e.message}"
        }
    }

    /**
     * 清除 USB endpoint 的 HALT 状态。
     * 设备从 fastboot 模式切换或其他异常后，endpoint 可能处于 STALL 状态，
     * 不清除的话 bulkTransfer 会一直超时。
     */
    private fun clearEndpointHalt(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        try {
            // USB 标准请求: CLEAR_FEATURE(ENDPOINT_HALT)
            // bmRequestType=0x02 (endpoint, host-to-device)
            // bRequest=0x01 (CLEAR_FEATURE)
            // wValue=0x0000 (ENDPOINT_HALT)
            // wIndex=endpoint address
            val result = conn.controlTransfer(
                0x02,       // bmRequestType: endpoint, host-to-device
                0x01,       // bRequest: CLEAR_FEATURE
                0x0000,     // wValue: ENDPOINT_HALT
                ep.address, // wIndex: endpoint address
                null,       // data
                0,          // length
                1000        // timeout
            )
            Log.d(TAG, "clearEndpointHalt ep=0x${ep.address.toString(16)}: result=$result")
        } catch (e: Exception) {
            Log.w(TAG, "clearEndpointHalt failed for ep=0x${ep.address.toString(16)}: ${e.message}")
        }
    }

    // ── Fastboot 命令 ──

    /**
     * 发送 fastboot 命令并读取响应。
     * 返回 FastbootResponse。
     */
    private fun executeCommand(command: String): FastbootResponse {
        synchronized(usbLock) {
            if (closed) return FastbootResponse.Error("连接已断开")
            val conn = connection ?: return FastbootResponse.Error("Not connected")
            val outEp = endpointOut ?: return FastbootResponse.Error("No OUT endpoint")
            val inEp = endpointIn ?: return FastbootResponse.Error("No IN endpoint")

            log(">>> $command")

            // 发送命令（带重试，重试前清除 halt）
            val cmdBytes = command.toByteArray()
            var sent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
            if (sent < 0 && !closed) {
                log("发送失败(sent=$sent), 清除halt后重试...")
                clearEndpointHalt(conn, outEp)
                clearEndpointHalt(conn, inEp)
                Thread.sleep(300)
                sent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
            }
            if (sent < 0) {
                val reason = if (closed) "连接已断开" else "sent=$sent"
                logError("发送命令失败: $command ($reason)")
                return FastbootResponse.Error("发送命令失败 ($reason)")
            }
            log("  已发送 $sent 字节")

            // 读取响应
            val resp = readResponse(conn, inEp)
            log("  响应: $resp")
            return resp
        }
    }

    /**
     * 从 IN 端点读取数据。
     * 两种方式都尝试：bulkTransfer（同步）和 UsbRequest（异步）。
     * 返回读取的字节数，无数据返回 0，失败返回 -1。
     */
    private fun usbRead(conn: UsbDeviceConnection, inEp: UsbEndpoint, buf: ByteArray, timeoutMs: Int): Int {
        // 1. UsbRequest（异步，对该设备兼容性更好）
        val req = UsbRequest()
        var usbrResult = -1
        try {
            req.initialize(conn, inEp)
            val byteBuf = ByteBuffer.allocate(buf.size)
            @Suppress("DEPRECATION")
            if (req.queue(byteBuf, buf.size)) {
                val completed = conn.requestWait(timeoutMs.toLong())
                if (completed != null) {
                    byteBuf.rewind()
                    val count = minOf(byteBuf.limit(), buf.size)
                    byteBuf.get(buf, 0, count)
                    var lastNonZero = -1
                    for (i in 0 until count) {
                        if (buf[i] != 0.toByte()) lastNonZero = i
                    }
                    usbrResult = if (lastNonZero >= 0) lastNonZero + 1 else 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "usbRead UsbRequest: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { req.close() } catch (_: Exception) {}
        }
        if (usbrResult > 0) {
            Log.d(TAG, "usbRead: UsbRequest OK, $usbrResult bytes")
            return usbrResult
        }
        if (usbrResult < 0) {
            Log.w(TAG, "usbRead: UsbRequest failed ($usbrResult), trying bulkTransfer")
        }

        // 2. bulkTransfer fallback（UsbRequest 返回 0 时才尝试）
        return try {
            conn.bulkTransfer(inEp, buf, buf.size, timeoutMs) ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "usbRead bulkTransfer: ${e.message}")
            -1
        }
    }

    /**
     * 读取 fastboot 响应。
     * 使用 maxPacketSize(512) buffer 逐包读取，避免 0xFE 填充问题。
     * 响应格式: 4字节标签(OKAY/FAIL/DATA/INFO) + 可变长度数据
     */
    private fun readResponse(conn: UsbDeviceConnection, inEp: UsbEndpoint): FastbootResponse {
        val infoMessages = mutableListOf<String>()
        val packetSize = inEp.maxPacketSize.coerceAtLeast(512)

        while (true) {
            val buf = ByteArray(packetSize)
            val read = usbRead(conn, inEp, buf, TIMEOUT_MS)
            if (read < 4) {
                Log.e(TAG, "readResponse: read=$read, closed=$closed")
                return FastbootResponse.Error("读取响应失败 (read=$read)")
            }

            val tag = String(buf, 0, 4)
            val payload = if (read > 4) String(buf, 4, read - 4).trim() else ""
            Log.d(TAG, "<<< $tag (read=$read, payload=${payload.take(100)})")

            when (tag) {
                "OKAY" -> return FastbootResponse.Okay(payload, infoMessages)
                "FAIL" -> return FastbootResponse.Error("FAIL: $payload", infoMessages)
                "DATA" -> {
                    val dataSize = try { payload.toInt(16) } catch (_: Exception) { 0 }
                    return FastbootResponse.DataReady(dataSize, infoMessages)
                }
                "INFO" -> {
                    infoMessages.add(payload)
                    Log.d(TAG, "INFO: $payload")
                }
                else -> return FastbootResponse.Error("未知响应标签: $tag")
            }
        }
    }

    // ── 公开命令 ──

    /**
     * 获取 fastboot 变量。
     * getvar:version, getvar:product, getvar:serialno, getvar:all 等
     */
    fun getVar(key: String): String {
        return try {
            val resp = executeCommand("getvar:$key")
            when (resp) {
                is FastbootResponse.Okay -> resp.data
                is FastbootResponse.Error -> resp.message
                else -> ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "getVar($key) failed: ${e.message}")
            ""
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
    synchronized(usbLock) {
        if (closed) return "连接已断开"
        val conn = connection ?: return "未连接"
        val outEp = endpointOut ?: return "无 OUT endpoint"
        val inEp = endpointIn ?: return "无 IN endpoint"

        log("刷入 $partition: ${data.size / 1024 / 1024}MB (${data.size} 字节)")

        // 1. 发送 download 命令
        val sizeHex = String.format("%08x", data.size)
        val downloadCmd = "download:$sizeHex"
        val cmdBytes = downloadCmd.toByteArray()
        val cmdSent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        log("download 命令: sent=$cmdSent")
        if (cmdSent < 0) return "发送download命令失败"
        Thread.sleep(50) // 等设备处理命令

        // 2. 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        log("DATA响应: $resp")
        if (resp !is FastbootResponse.DataReady) {
            return "下载请求被拒绝: ${(resp as? FastbootResponse.Error)?.message ?: "未知错误"}"
        }

        // 等设备准备接收缓冲区
        Thread.sleep(200)

        // 3. 分块发送数据
        log("开始传输数据...")
        var offset = 0
        var chunkCount = 0
        while (offset < data.size) {
            val len = minOf(FLASH_CHUNK_SIZE, data.size - offset)
            var sent = conn.bulkTransfer(outEp, data, offset, len, TIMEOUT_MS * 3)
            if (sent < 0 && len > 4096) {
                // 大块失败时尝试小块
                log("大块传输失败(len=$len), 尝试小块(4096)...")
                sent = conn.bulkTransfer(outEp, data, offset, 4096, TIMEOUT_MS * 3)
            }
            if (sent < 0) {
                logError("传输失败: offset=$offset/${data.size}, chunk=$chunkCount, len=$len, sent=$sent")
                return "数据传输失败 (offset=$offset)"
            }
            offset += sent
            chunkCount++
            val progress = (offset.toLong() * 100 / data.size).toInt()
            progressCallback?.invoke(progress)
            if (chunkCount % 10 == 0 || offset >= data.size) {
                log("传输进度: ${progress}% ($offset/${data.size})")
            }
        }
        log("数据传输完成: $chunkCount 块, $offset 字节")

        // 4. 读取最终 OKAY 响应
        log("等待设备确认...")
        val finalResp = readResponse(conn, inEp)
        log("最终响应: $finalResp")
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
    }

    /**
     * 获取设备详细信息
     */
    fun getDeviceInfo(): Map<String, String> {
        // 优先用 getvar:all 一次性获取所有变量
        val all = getVarAll()
        if (all.isNotEmpty()) {
            // 按重要性排序，选择用户关心的字段
            val displayOrder = listOf(
                "product", "serialno", "variant",
                "slot-count", "current-slot",
                "secure", "unlocked",
                "battery-voltage", "battery-soc-ok",
                "hw-revision", "erase-block-size", "logical-block-size",
                "max-download-size", "kernel",
                "has-slot:boot", "has-slot:modem",
                "version-bootloader", "version-baseband"
            )
            val info = linkedMapOf<String, String>()
            for (key in displayOrder) {
                all[key]?.let { info[label(key)] = it }
            }
            // 加上 displayOrder 中没列到的其他变量（跳过 partition 相关的大量条目）
            for ((key, value) in all) {
                if (key !in displayOrder && value.isNotEmpty()
                    && !key.startsWith("partition-")
                    && !key.startsWith("has-slot:")
                ) {
                    info[label(key)] = value
                }
            }
            return info
        }

        // fallback: 逐个查询
        val info = mutableMapOf<String, String>()
        val keys = listOf("product", "serialno", "variant", "slot-count", "battery-voltage", "unlocked", "secure")
        for (key in keys) {
            try {
                val value = getVar(key)
                if (value.isNotEmpty()) info[label(key)] = value
            } catch (_: Exception) {}
        }
        return info
    }

    /**
     * 获取所有 fastboot 变量（getvar:all）。
     * getvar:all 返回 INFO 行格式的 key:value 对。
     */
    fun getVarAll(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        synchronized(usbLock) {
            if (closed) return info
            val conn = connection ?: return info
            val outEp = endpointOut ?: return info
            val inEp = endpointIn ?: return info

            Log.d(TAG, ">>> getvar:all")
            val cmdBytes = "getvar:all".toByteArray()
            conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
            Thread.sleep(50) // 等设备处理命令

            val packetSize = inEp.maxPacketSize.coerceAtLeast(512)
            while (true) {
                val buf = ByteArray(packetSize)
                val read = usbRead(conn, inEp, buf, TIMEOUT_MS)
                if (read < 4) break
                val tag = String(buf, 0, 4)
                val payload = if (read > 4) String(buf, 4, read - 4).trim() else ""
                when (tag) {
                    "OKAY" -> break
                    "INFO" -> {
                        val colonIdx = payload.indexOf(':')
                        if (colonIdx > 0) {
                            val key = payload.substring(0, colonIdx).trim()
                            val value = payload.substring(colonIdx + 1).trim()
                            if (key.isNotEmpty()) info[key] = value
                        }
                        Log.d(TAG, "getvar:all INFO: $payload")
                    }
                    else -> break
                }
            }
            return info
        }
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
    synchronized(usbLock) {
        if (closed) return "连接已断开"
        val conn = connection ?: return "未连接"
        val outEp = endpointOut ?: return "无 OUT endpoint"
        val inEp = endpointIn ?: return "无 IN endpoint"

        Log.d(TAG, "boot: ${data.size} bytes")

        // 1. 发送 boot 命令（不带 size，由协议自动处理）
        val cmdBytes = "boot".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        Thread.sleep(50)

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
            progressCallback?.invoke((offset.toLong() * 100 / data.size).toInt())
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
    }

    /**
     * 上传数据到设备（stage）。
     * fastboot stage <size> → DATA → 发送数据
     * 数据暂存到设备内存，配合 oem 命令使用。
     * @param progressCallback 进度回调 (0-100)
     */
    fun stage(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String {
    synchronized(usbLock) {
        if (closed) return "连接已断开"
        val conn = connection ?: return "未连接"
        val outEp = endpointOut ?: return "无 OUT endpoint"
        val inEp = endpointIn ?: return "无 IN endpoint"

        Log.d(TAG, "stage: ${data.size} bytes")

        // 1. 发送 stage 命令
        val sizeHex = String.format("%08x", data.size)
        val cmdBytes = "stage:$sizeHex".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        Thread.sleep(50)

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
            progressCallback?.invoke((offset.toLong() * 100 / data.size).toInt())
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
    }

    /**
     * 从设备下载数据（fetch）。
     * fastboot fetch → 设备返回 DATA → 读取数据
     * @return Pair(resultMessage, data) 或 null
     */
    fun fetch(): Pair<String, ByteArray?> {
    synchronized(usbLock) {
        if (closed) return "连接已断开" to null
        val conn = connection ?: return "未连接" to null
        val outEp = endpointOut ?: return "无 OUT endpoint" to null
        val inEp = endpointIn ?: return "无 IN endpoint" to null

        Log.d(TAG, ">>> fetch")
        val cmdBytes = "fetch".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        Thread.sleep(50)

        // 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        if (resp !is FastbootResponse.DataReady) {
            val msg = (resp as? FastbootResponse.Error)?.message ?: "fetch 请求被拒绝"
            return msg to null
        }

        // 读取数据（使用 UsbRequest）
        val dataSize = resp.dataSize
        Log.d(TAG, "fetch: expecting $dataSize bytes")
        val buffer = ByteArray(dataSize)
        var totalRead = 0
        while (totalRead < dataSize) {
            val toRead = minOf(FLASH_CHUNK_SIZE, dataSize - totalRead)
            val chunk = ByteArray(toRead)
            val n = usbRead(conn, inEp, chunk, TIMEOUT_MS * 3)
            if (n < 0) return "数据接收失败 (offset=$totalRead)" to null
            System.arraycopy(chunk, 0, buffer, totalRead, n)
            totalRead += n
        }

        // 读取最终 OKAY
        readResponse(conn, inEp)
        Log.d(TAG, "fetch: received $totalRead bytes")
        return "数据下载成功 (${totalRead / 1024}KB)" to buffer
    }
    }

    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
    }

    /** 请求 USB 权限（静默，不等待结果） */
    private fun requestUsbPermission(usbDevice: UsbDevice) {
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(usbDevice, pi)
        } catch (e: Exception) {
            Log.w(TAG, "requestUsbPermission failed: ${e.message}")
        }
    }

    /**
     * 重新扫描 USB 设备列表，找到与原始设备匹配的新 UsbDevice 引用。
     * 关闭连接后旧引用可能失效，需要重新获取。
     */
    private fun refreshUsbDevice(original: FastbootDevice): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            if (device.deviceName == original.usbDevice.deviceName) return device
            try {
                if (device.vendorId == original.usbDevice.vendorId &&
                    device.productId == original.usbDevice.productId &&
                    device.serialNumber == original.usbDevice.serialNumber) return device
            } catch (_: Exception) {}
        }
        // 按 vendorId 匹配（最后手段）
        for (device in usbManager.deviceList.values) {
            if (device.vendorId == original.usbDevice.vendorId && isFastbootDevice(device)) return device
        }
        return null
    }

    /**
     * 尝试打开 USB 设备，有权限则返回 UsbDeviceConnection，无权限返回 null。
     */
    private fun tryOpenDevice(usbDevice: UsbDevice): UsbDeviceConnection? {
        return try {
            usbManager.openDevice(usbDevice)
        } catch (e: Exception) {
            Log.w(TAG, "tryOpenDevice failed: ${e.message}")
            null
        }
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
