package com.wearadb.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Manages USB ADB device scanning, permission handling, and connection lifecycle.
 * Scans for devices with ADB interface (class=0xFF, sub=0x42, proto=0x01).
 */
class UsbAdbManager(
    private val context: Context,
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
    /** 链路失效回调（读取线程 EOF / 设备拔出广播），由仓储层注入以更新连接状态。 */
    private val onConnectionLost: (() -> Unit)? = null,
    private val logCallback: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UsbAdbManager"
        private const val ACTION_USB_PERMISSION = "com.wearadb.USB_ADB_PERMISSION"
        // ADB interface: same as fastboot but protocol=0x01 instead of 0x03
        private const val ADB_INTERFACE_CLASS = 255       // 0xFF
        private const val ADB_INTERFACE_SUBCLASS = 66     // 0x42
        private const val ADB_INTERFACE_PROTOCOL = 1     // 0x01
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var closed = false

    private var currentConnection: UsbAdbConnection? = null
    private var currentTransport: UsbAdbTransport? = null

    /** 当前已连接的物理设备（用于 DETACHED 广播比对）。 */
    @Volatile private var currentDevice: UsbDevice? = null

    /** 防重入：同一次断链（读取线程死亡 + DETACHED 广播先后到达）只上报一次。 */
    @Volatile private var linkLostReported = false

    // USB permission receiver
    private var permissionGranted = false
    private var pendingDevice: UsbDevice? = null

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
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            permissionGranted = true
                            Log.d(TAG, "USB permission granted for ${device.deviceName}")
                        }
                    } else {
                        Log.w(TAG, "USB permission denied")
                    }
                }
            }
        }
    }

    /** USB 设备拔出接收器（2026-09-11 修复：此前全工程不监听 DETACHED，设备拔出 App 毫无反应）。 */
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }
            val current = currentDevice ?: return
            // 只处理当前会话的设备（deviceName 是枚举路径 /dev/bus/usb/…，重插后会变）
            if (device != null && device.deviceName == current.deviceName) {
                log("USB设备已拔出: ${current.deviceName}")
                handleConnectionLost()
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
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbDetachReceiver, detachFilter)
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback?.invoke(msg)
    }

    // ── Device scanning ──

    /**
     * Scan for USB devices with ADB interface.
     * Returns list of UsbDeviceInfo with device details.
     */
    fun scanDevices(): List<UsbAdbDeviceInfo> {
        val devices = mutableListOf<UsbAdbDeviceInfo>()
        for (device in usbManager.deviceList.values) {
            if (hasAdbInterface(device)) {
                val serial = try { device.serialNumber ?: device.deviceName } catch (_: Exception) { device.deviceName }
                val product = try { device.productName ?: "" } catch (_: Exception) { "" }
                val manufacturer = try { device.manufacturerName ?: "" } catch (_: Exception) { "" }
                devices.add(UsbAdbDeviceInfo(
                    usbDevice = device,
                    serialNumber = serial,
                    productName = product,
                    manufacturerName = manufacturer
                ))
            }
        }
        log("扫描到 ${devices.size} 个有线ADB设备")
        return devices
    }

    private fun hasAdbInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == ADB_INTERFACE_CLASS &&
                iface.interfaceSubclass == ADB_INTERFACE_SUBCLASS &&
                iface.interfaceProtocol == ADB_INTERFACE_PROTOCOL
            ) {
                return true
            }
        }
        return false
    }

    // ── Connection ──

    /**
     * Connect to a USB ADB device.
     * Returns true if connection and authentication succeed.
     */
    suspend fun connect(deviceInfo: UsbAdbDeviceInfo): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                closed = false
                linkLostReported = false
                log("连接有线ADB设备: ${deviceInfo.serialNumber}...")

                val usbDevice = deviceInfo.usbDevice

                // 1. Try to open device
                log("打开设备... hasPermission=${usbManager.hasPermission(usbDevice)}")
                var conn = tryOpenDevice(usbDevice)

                // 2. If no permission, request and wait
                if (conn == null) {
                    log("需要USB权限, 正在请求...")
                    requestPermission(usbDevice)
                    val waitStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - waitStart < 15000) {
                        kotlinx.coroutines.delay(500)
                        conn = tryOpenDevice(usbDevice)
                        if (conn != null) break
                    }
                    if (conn == null) {
                        log("USB权限获取失败")
                        return@withContext false
                    }
                }
                log("设备已打开")

                // 3. Find ADB interface and endpoints
                var foundInterface: UsbInterface? = null
                var outEp: UsbEndpoint? = null
                var inEp: UsbEndpoint? = null

                for (i in 0 until usbDevice.interfaceCount) {
                    val iface = usbDevice.getInterface(i)
                    if (iface.interfaceClass == ADB_INTERFACE_CLASS &&
                        iface.interfaceSubclass == ADB_INTERFACE_SUBCLASS &&
                        iface.interfaceProtocol == ADB_INTERFACE_PROTOCOL
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
                    log("未找到ADB接口")
                    conn.close()
                    return@withContext false
                }

                // 4. Claim interface
                try { conn.releaseInterface(foundInterface) } catch (_: Exception) {}
                if (!conn.claimInterface(foundInterface, true)) {
                    log("claimInterface失败")
                    conn.close()
                    return@withContext false
                }
                kotlinx.coroutines.delay(300)

                log("ADB接口: id=${foundInterface.id} OUT=0x${outEp.address.toString(16)}(${outEp.maxPacketSize}) IN=0x${inEp.address.toString(16)}(${inEp.maxPacketSize})")

                // 4a. 清除端点halt状态（USB读写前的标准操作）
                try {
                    conn.controlTransfer(0x02, 0x01, 0x0000, inEp.address, null, 0, 1000)
                    log("  CLEAR_HALT IN=OK")
                } catch (e: Exception) {
                    log("  CLEAR_HALT IN=${e.message}")
                }
                try {
                    conn.controlTransfer(0x02, 0x01, 0x0000, outEp.address, null, 0, 1000)
                    log("  CLEAR_HALT OUT=OK")
                } catch (e: Exception) {
                    log("  CLEAR_HALT OUT=${e.message}")
                }
                Thread.sleep(100)

                // 4b. 清空IN端点残留数据
                log("清空IN端点...")
                val drainBuf = ByteArray(inEp.maxPacketSize.coerceAtLeast(512))
                while (true) {
                    val n = conn.bulkTransfer(inEp, drainBuf, drainBuf.size, 100)
                    if (n <= 0) break
                    log("  清空了 $n 字节")
                }

                // 5. 创建传输层和连接
                log("创建ADB连接...")
                val transport = UsbAdbTransport(conn, outEp, inEp)
                val adbConn = UsbAdbConnection(
                    transport, privateKey, certificate,
                    logCallback = logCallback,
                    onLinkDead = { handleConnectionLost() }
                )

                // 7. Perform ADB handshake
                log("开始ADB握手...")
                try {
                    val ok = adbConn.connect()
                    log("connect()=$ok")
                    if (!ok) {
                        log("ADB握手失败")
                        transport.close()
                        conn.close()
                        return@withContext false
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "connect() THREW: ${e.javaClass.simpleName}: ${e.message}", e)
                    log("connect()异常: ${e.javaClass.simpleName}: ${e.message}")
                    transport.close()
                    conn.close()
                    return@withContext false
                }

                currentTransport = transport
                currentConnection = adbConn
                currentDevice = usbDevice
                log("有线ADB连接成功!")
                true
            } catch (e: Exception) {
                log("连接异常: ${e.message}")
                false
            }
        }
    }

    fun disconnect() {
        closed = true
        currentDevice = null
        currentConnection?.disconnect()
        currentConnection = null
        currentTransport?.close()
        currentTransport = null
        Log.d(TAG, "Disconnected")
    }

    /**
     * 链路失效统一处理（UsbAdbConnection 读取线程 EOF / USB 设备拔出广播）。
     *
     * 幂等：读取线程死亡与 DETACHED 广播可能先后到达，同一次断链只清理/上报一次
     * （[linkLostReported] 在每次 connect() 成功路径前复位）。
     * 用户主动 [disconnect] 不经过这里，不会产生"链路失效"误报。
     */
    private fun handleConnectionLost() {
        if (linkLostReported) return
        linkLostReported = true
        val conn = currentConnection
        val transport = currentTransport
        val device = currentDevice
        currentConnection = null
        currentTransport = null
        currentDevice = null
        log("链路失效，清理连接（device=${device?.deviceName ?: "unknown"}）")
        try { conn?.disconnect() } catch (_: Exception) {}
        try { transport?.close() } catch (_: Exception) {}
        try { onConnectionLost?.invoke() } catch (_: Exception) {}
    }

    val isConnected: Boolean get() = currentConnection?.isConnected == true

    fun getConnection(): UsbAdbConnection? = currentConnection

    private fun requestPermission(usbDevice: UsbDevice) {
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(usbDevice, pi)
        } catch (e: Exception) {
            Log.w(TAG, "requestPermission failed: ${e.message}")
        }
    }

    private fun tryOpenDevice(usbDevice: UsbDevice): UsbDeviceConnection? {
        return try {
            usbManager.openDevice(usbDevice)
        } catch (e: Exception) {
            Log.w(TAG, "tryOpenDevice failed: ${e.message}")
            null
        }
    }

    fun destroy() {
        disconnect()
        try { context.unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
    }
}

/**
 * Info about a detected USB ADB device.
 */
data class UsbAdbDeviceInfo(
    val usbDevice: UsbDevice,
    val serialNumber: String,
    val productName: String,
    val manufacturerName: String
) {
    val displayName: String
        get() = when {
            productName.isNotEmpty() && manufacturerName.isNotEmpty() -> "$manufacturerName $productName"
            productName.isNotEmpty() -> productName
            else -> serialNumber
        }
}
