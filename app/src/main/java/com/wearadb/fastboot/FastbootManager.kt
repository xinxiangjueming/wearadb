package com.wearadb.fastboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import com.wearadb.R
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

        /** fastboot 变量名 → 显示名资源 id（文案走多语言，不再硬编码中文） */
        private val LABEL_RES = mapOf(
            "product" to R.string.fb_var_product,
            "serialno" to R.string.fb_var_serialno,
            "variant" to R.string.fb_var_variant,
            "slot-count" to R.string.fb_var_slot_count,
            "current-slot" to R.string.fb_var_current_slot,
            "secure" to R.string.fb_var_secure,
            "unlocked" to R.string.fb_var_unlocked,
            "battery-voltage" to R.string.fb_var_battery_voltage,
            "battery-soc-ok" to R.string.fb_var_battery_soc_ok,
            "hw-revision" to R.string.fb_var_hw_revision,
            "erase-block-size" to R.string.fb_var_erase_block_size,
            "logical-block-size" to R.string.fb_var_logical_block_size,
            "max-download-size" to R.string.fb_var_max_download_size,
            "kernel" to R.string.fb_var_kernel,
            "has-slot:boot" to R.string.fb_var_has_slot_boot,
            "has-slot:modem" to R.string.fb_var_has_slot_modem,
            "has-slot:system" to R.string.fb_var_has_slot_system,
            "version-bootloader" to R.string.fb_var_version_bootloader,
            "version-baseband" to R.string.fb_var_version_baseband,
            "dpstatus" to R.string.fb_var_dpstatus,
            "socid" to R.string.fb_var_socid,
            "snapshot-update-status" to R.string.fb_var_snapshot_update_status,
            "is-userspace" to R.string.fb_var_is_userspace
        )
    }

    /** 变量名 → 本地化显示名；未知 key 原样返回。 */
    private fun label(key: String): String {
        val res = LABEL_RES[key] ?: return key
        return context.getString(res)
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
            log(context.getString(R.string.fb_log_connecting, device.serialNumber))
            disconnect()
            closed = false  // 重置，允许新连接

            val usbDevice = device.usbDevice

            // 打印设备所有接口信息
            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                log(
                    context.getString(
                        R.string.fb_log_iface,
                        i,
                        iface.interfaceClass.toString(16),
                        iface.interfaceSubclass.toString(16),
                        iface.interfaceProtocol.toString(16),
                        iface.endpointCount
                    )
                )
            }

            // 1. 尝试直接打开
            val hasPerm = usbManager.hasPermission(usbDevice)
            log(context.getString(R.string.fb_log_open_device, hasPerm))
            var conn = tryOpenDevice(usbDevice)

            // 2. 没权限则请求并轮询
            if (conn == null) {
                log(context.getString(R.string.fb_log_need_permission))
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
                        log(context.getString(R.string.fb_log_permission_ok, System.currentTimeMillis() - waitStart))
                        break
                    }
                    if (permissionGranted) {
                        delay(300)
                        conn = tryOpenDevice(usbDevice)
                        if (conn != null) {
                            log(context.getString(R.string.fb_log_permission_ok_broadcast))
                            break
                        }
                    }
                }

                if (conn == null) {
                    logError(context.getString(R.string.fb_log_permission_timeout, usbManager.hasPermission(usbDevice)))
                    return@withContext false
                }
            } else {
                log(context.getString(R.string.fb_log_authorized_open))
            }

            // 3. 查找 fastboot 接口
            log(context.getString(R.string.fb_log_finding_iface))
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
                    log(context.getString(R.string.fb_log_iface_std, i))
                    break
                }
            }

            if (foundInterface == null) {
                log(context.getString(R.string.fb_log_iface_std_miss))
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
                            log(context.getString(R.string.fb_log_iface_loose, i, iface.interfaceSubclass.toString(16)))
                            break
                        }
                    }
                }
            }

            if (foundInterface == null || outEp == null || inEp == null) {
                logError(context.getString(R.string.fb_log_no_iface))
                conn.close()
                return@withContext false
            }

            // 4. 先释放再 claim（确保内核驱动完全脱离）
            try { conn.releaseInterface(foundInterface) } catch (_: Exception) {}
            if (!conn.claimInterface(foundInterface, true)) {
                logError(context.getString(R.string.fb_log_claim_failed))
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

            log(context.getString(R.string.fb_log_iface_claimed))
            log("  iface: id=${foundInterface.id} alt=${foundInterface.alternateSetting}")
            log("  OUT: 0x${outEp.address.toString(16)} maxPkt=${outEp.maxPacketSize}")
            log("  IN:  0x${inEp.address.toString(16)} maxPkt=${inEp.maxPacketSize}")

            // 5. 尝试激活接口（兼容 TWRP 等非标准实现）
            log(context.getString(R.string.fb_log_activating))
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
            log(context.getString(R.string.fb_log_device_reset))
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
                log(context.getString(R.string.fb_log_getstatus_error, e.message ?: ""))
            }
            Thread.sleep(300)

            // 8. 清除 halt（reset 后再清一次）
            clearEndpointHalt(conn, outEp)
            clearEndpointHalt(conn, inEp)
            Thread.sleep(200)

            // 9. 消费 IN 端点上可能残留的数据
            log(context.getString(R.string.fb_log_clear_in))
            val drainBuf = ByteArray(512)
            while (true) {
                val n = conn.bulkTransfer(inEp, drainBuf, 512, 100)
                if (n <= 0) break
                log(context.getString(R.string.fb_log_cleared_bytes, n))
            }

            // 10. 验证通信（失败则重新扫描设备后重试）
            log(context.getString(R.string.fb_log_test_comm))
            var testResp = trySendTestCommand(conn, outEp, inEp)
            log("getvar:product → ${testResp.second}")

            if (!testResp.first) {
                logError(context.getString(R.string.fb_log_comm_failed, testResp.second))
                logError(context.getString(R.string.fb_log_test_on_pc))
                logError(context.getString(R.string.fb_log_maybe_otg))
                disconnect()
                return@withContext false
            }

            log(context.getString(R.string.fb_log_connected))
            true
        } catch (e: Exception) {
            logError(context.getString(R.string.fb_log_connect_exception, e.message ?: ""))
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
        logCallback?.invoke(context.getString(R.string.fb_log_error_prefix, msg))
    }

    /**
     * 直接发送测试命令（使用 UsbRequest 异步读取）。
     * 返回 `(是否成功, 展示文案)`：成功判定由 Boolean 承载，
     * 文案仅供展示（可本地化），调用方不得再靠中文关键字判断成败。
     */
    private fun trySendTestCommand(
        conn: UsbDeviceConnection, outEp: UsbEndpoint, inEp: UsbEndpoint
    ): Pair<Boolean, String> {
        return try {
            // 1. 发送命令
            val cmd = "getvar:product".toByteArray()
            val sent = conn.bulkTransfer(outEp, cmd, cmd.size, TIMEOUT_MS)
            if (sent < 0) return false to context.getString(R.string.fb_r_test_send_failed, sent)
            log(context.getString(R.string.fb_log_test_sent, cmd.size))

            // 2. 读取响应（不加延迟，立即读取）
            val readBuf = ByteArray(4096)
            val bytesRead = usbRead(conn, inEp, readBuf, TIMEOUT_MS)
            log(context.getString(R.string.fb_log_test_read, bytesRead))
            if (bytesRead < 4) return false to context.getString(R.string.fb_r_test_resp_short, bytesRead)

            val tag = String(readBuf, 0, 4)
            log(context.getString(R.string.fb_log_test_tag, tag, bytesRead))

            return when (tag) {
                "OKAY" -> {
                    val data = if (bytesRead > 4) String(readBuf, 4, bytesRead - 4).trim() else ""
                    true to "OK: $data"
                }
                "FAIL" -> {
                    val data = if (bytesRead > 4) String(readBuf, 4, bytesRead - 4).trim() else ""
                    false to context.getString(R.string.fb_r_test_fail, data)
                }
                "INFO" -> {
                    val info = if (bytesRead > 4) String(readBuf, 4, bytesRead - 4).trim() else ""
                    true to "OK: (INFO: $info)"
                }
                else -> false to context.getString(R.string.fb_r_test_unknown_tag, tag)
            }
        } catch (e: Exception) {
            false to context.getString(R.string.fb_r_test_exception, e.message ?: "")
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
            if (closed) return FastbootResponse.Error(context.getString(R.string.fb_r_disconnected))
            val conn = connection ?: return FastbootResponse.Error("Not connected")
            val outEp = endpointOut ?: return FastbootResponse.Error("No OUT endpoint")
            val inEp = endpointIn ?: return FastbootResponse.Error("No IN endpoint")

            log(">>> $command")

            // 发送命令（带重试，重试前清除 halt）
            val cmdBytes = command.toByteArray()
            var sent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
            if (sent < 0 && !closed) {
                log(context.getString(R.string.fb_log_send_retry, sent))
                clearEndpointHalt(conn, outEp)
                clearEndpointHalt(conn, inEp)
                Thread.sleep(300)
                sent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
            }
            if (sent < 0) {
                val reason = if (closed) context.getString(R.string.fb_r_disconnected) else "sent=$sent"
                logError(context.getString(R.string.fb_log_send_cmd_failed, command, reason))
                return FastbootResponse.Error(context.getString(R.string.fb_r_send_cmd_failed, reason))
            }
            log(context.getString(R.string.fb_log_sent_bytes, sent))

            // 读取响应
            val resp = readResponse(conn, inEp)
            log(context.getString(R.string.fb_log_response, resp))
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
                return FastbootResponse.Error(context.getString(R.string.fb_r_read_resp_failed, read))
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
                else -> return FastbootResponse.Error(context.getString(R.string.fb_r_unknown_resp_tag, tag))
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
            is FastbootResponse.Okay -> context.getString(R.string.fb_r_rebooting)
            is FastbootResponse.Error -> resp.message
            else -> context.getString(R.string.fb_r_reboot_sent)
        }
    }

    /**
     * 重启到 recovery
     */
    fun rebootRecovery(): String {
        val resp = executeCommand("reboot-recovery")
        return when (resp) {
            is FastbootResponse.Okay -> context.getString(R.string.fb_r_rebooting_recovery)
            is FastbootResponse.Error -> resp.message
            else -> context.getString(R.string.fb_r_reboot_sent)
        }
    }

    /**
     * 重启回 bootloader
     */
    fun rebootBootloader(): String {
        val resp = executeCommand("reboot-bootloader")
        return when (resp) {
            is FastbootResponse.Okay -> context.getString(R.string.fb_r_rebooting_bootloader)
            is FastbootResponse.Error -> resp.message
            else -> context.getString(R.string.fb_r_reboot_sent)
        }
    }

    /**
     * 擦除分区
     */
    fun erase(partition: String): String {
        val resp = executeCommand("erase:$partition")
        return when (resp) {
            is FastbootResponse.Okay -> context.getString(R.string.fb_r_erase_ok, partition)
            is FastbootResponse.Error -> context.getString(R.string.fb_r_erase_failed, resp.message)
            else -> context.getString(R.string.fb_r_erase_done)
        }
    }

    /**
     * 执行 OEM 命令
     */
    fun oem(command: String): String {
        val resp = executeCommand("oem $command")
        return when (resp) {
            is FastbootResponse.Okay -> resp.data.ifEmpty { context.getString(R.string.fb_r_oem_ok) }
            is FastbootResponse.Error -> context.getString(R.string.fb_r_oem_failed, resp.message)
            else -> context.getString(R.string.fb_r_oem_done)
        }
    }

    /**
     * 刷入分区。
     * 协议流程: download <size> → DATA → 发送数据 → OKAY
     * @param progressCallback 进度回调 (0-100)
     */
    fun flash(partition: String, data: ByteArray, progressCallback: ((Int) -> Unit)? = null): Pair<Boolean, String> {
        log(context.getString(R.string.fb_log_flashing, partition, data.size / 1024 / 1024, data.size))
        val sizeHex = String.format("%08x", data.size)
        val result = downloadToDevice("download:$sizeHex", data, context.getString(R.string.fb_r_flash_op_name), progressCallback)
        // 成败判据来自 ASCII 协议标记 "OK:"，与本地化文案无关；文案仅用于展示
        return if (result.startsWith("OK:")) {
            true to context.getString(R.string.fb_r_flash_ok, partition, data.size / 1024)
        } else {
            // "OK:"/"ERR:" 是 downloadToDevice 的 ASCII 协议标记，本地化时仍把它作为被替换对象
            false to result.replace("OK:", context.getString(R.string.fb_r_flash_op_name))
                .replace("ERR:", context.getString(R.string.fb_r_flash_failed_prefix))
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
            is FastbootResponse.Okay -> context.getString(R.string.fb_r_unlock_ok)
            is FastbootResponse.Error -> context.getString(R.string.fb_r_unlock_failed, resp.message)
            else -> context.getString(R.string.fb_r_unlock_sent)
        }
    }

    /**
     * 锁定 bootloader。
     * fastboot flashing lock
     */
    fun flashingLock(): String {
        val resp = executeCommand("flashing lock")
        return when (resp) {
            is FastbootResponse.Okay -> context.getString(R.string.fb_r_lock_ok)
            is FastbootResponse.Error -> context.getString(R.string.fb_r_lock_failed, resp.message)
            else -> context.getString(R.string.fb_r_lock_sent)
        }
    }

    /**
     * 临时启动镜像（不刷入）。
     * fastboot boot <image>
     * @param progressCallback 进度回调 (0-100)
     */
    fun boot(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String {
        val result = downloadToDevice("boot", data, "boot", progressCallback)
        return when {
            result.startsWith("OK:") -> context.getString(R.string.fb_r_tempboot_ok)
            else -> result
        }
    }

    /**
     * 上传数据到设备（stage）。
     * 数据暂存到设备内存，配合 oem 命令使用。
     * @param progressCallback 进度回调 (0-100)
     */
    fun stage(data: ByteArray, progressCallback: ((Int) -> Unit)? = null): String {
        val sizeHex = String.format("%08x", data.size)
        val result = downloadToDevice("stage:$sizeHex", data, "stage", progressCallback)
        return when {
            result.startsWith("OK:") -> context.getString(R.string.fb_r_stage_ok, data.size / 1024)
            else -> result
        }
    }

    /**
     * Common download flow shared by flash/boot/stage.
     * 1. Send command → 2. Read DATA → 3. Send data chunks → 4. Read OKAY
     * @return "OK:..." on success, "ERR:..." on failure
     */
    private fun downloadToDevice(
        command: String,
        data: ByteArray,
        operationName: String,
        progressCallback: ((Int) -> Unit)? = null
    ): String = synchronized(usbLock) {
        if (closed) return@synchronized context.getString(R.string.fb_r_err_disconnected)
        val conn = connection ?: return@synchronized context.getString(R.string.fb_r_err_not_connected)
        val outEp = endpointOut ?: return@synchronized context.getString(R.string.fb_r_err_no_out_ep)
        val inEp = endpointIn ?: return@synchronized context.getString(R.string.fb_r_err_no_in_ep)

        // 1. Send command
        val cmdBytes = command.toByteArray()
        val cmdSent = conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        log(context.getString(R.string.fb_log_cmd_sent, operationName, cmdSent))
        if (cmdSent < 0) return@synchronized context.getString(R.string.fb_r_err_send_failed)
        Thread.sleep(50)

        // 2. Read DATA response
        val resp = readResponse(conn, inEp)
        log(context.getString(R.string.fb_log_data_response, resp))
        if (resp !is FastbootResponse.DataReady) {
            return@synchronized context.getString(R.string.fb_r_err_request_rejected, operationName, (resp as? FastbootResponse.Error)?.message ?: context.getString(R.string.unknown_error))
        }
        Thread.sleep(200) // Wait for device to prepare receive buffer

        // 3. Send data in chunks
        log(context.getString(R.string.fb_log_transfer_start, data.size))
        var offset = 0
        while (offset < data.size) {
            if (closed) return@synchronized context.getString(R.string.fb_r_err_transfer_aborted)
            val len = minOf(FLASH_CHUNK_SIZE, data.size - offset)
            var sent = conn.bulkTransfer(outEp, data, offset, len, TIMEOUT_MS * 3)
            if (sent < 0 && len > 4096) {
                log(context.getString(R.string.fb_log_chunk_retry, len))
                sent = conn.bulkTransfer(outEp, data, offset, 4096, TIMEOUT_MS * 3)
            }
            if (sent < 0) {
                logError(context.getString(R.string.fb_log_transfer_failed, offset, data.size))
                return@synchronized context.getString(R.string.fb_r_err_transfer_failed, offset)
            }
            offset += sent
            progressCallback?.invoke((offset.toLong() * 100 / data.size).toInt())
        }
        log(context.getString(R.string.fb_log_transfer_done, offset))

        // 4. Read final response
        val finalResp = readResponse(conn, inEp)
        log(context.getString(R.string.fb_log_final_response, finalResp))
        when (finalResp) {
            is FastbootResponse.Okay -> {
                progressCallback?.invoke(100)
                "OK: ${finalResp.data}"
            }
            is FastbootResponse.Error -> "ERR: ${finalResp.message}"
            else -> "OK:"
        }
    }

    /**
     * 从设备下载数据（fetch）。
     * fastboot fetch → 设备返回 DATA → 读取数据
     * @return Pair(resultMessage, data) 或 null
     */
    fun fetch(): Pair<String, ByteArray?> {
    synchronized(usbLock) {
        if (closed) return context.getString(R.string.fb_r_disconnected) to null
        val conn = connection ?: return context.getString(R.string.fb_r_not_connected) to null
        val outEp = endpointOut ?: return context.getString(R.string.fb_r_no_out_ep) to null
        val inEp = endpointIn ?: return context.getString(R.string.fb_r_no_in_ep) to null

        Log.d(TAG, ">>> fetch")
        val cmdBytes = "fetch".toByteArray()
        conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        Thread.sleep(50)

        // 读取 DATA 响应
        val resp = readResponse(conn, inEp)
        if (resp !is FastbootResponse.DataReady) {
            val msg = (resp as? FastbootResponse.Error)?.message ?: context.getString(R.string.fb_r_fetch_rejected)
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
            if (n < 0) return context.getString(R.string.fb_r_recv_failed, totalRead) to null
            System.arraycopy(chunk, 0, buffer, totalRead, n)
            totalRead += n
        }

        // 读取最终 OKAY
        readResponse(conn, inEp)
        Log.d(TAG, "fetch: received $totalRead bytes")
        return context.getString(R.string.fb_r_download_ok, totalRead / 1024) to buffer
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
