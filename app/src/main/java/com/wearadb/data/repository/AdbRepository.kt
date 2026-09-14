package com.wearadb.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.wearadb.adb.AdbOutputParser
import com.wearadb.adb.AdvancedOps
import com.wearadb.adb.UsbAdbRepository
import com.wearadb.adb.WearAdbConnectionManager
import com.wearadb.data.model.*
import com.wearadb.log.WearAdbLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

// ── 连接状态 ──
enum class ConnectionState {
    DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR
}

// ── 发现的设备 ──
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val isPairing: Boolean = false
)

// ── 配对结果 ──
data class PairingResult(
    val success: Boolean,
    val host: String,
    val port: Int,
    val message: String = ""
)

// ── 拉取结果 ──
data class PullResult(
    val success: Boolean,
    val data: ByteArray?,
    val message: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PullResult) return false
        return success == other.success && data.contentEquals(other.data) && message == other.message
    }
    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + message.hashCode()
        return result
    }
}

// ── 导出安装包结果（文案由 UI 层按当前语言组装，仓储/VM 只回传事实）──

sealed interface ApkExtractResult {
    /**
     * @param fileName  落盘文件名（单 APK 为 `<包名>.apk`；含 split 时为 `<包名>.apks`）
     * @param location  展示用的位置（公共目录为相对的 `Download/WearAdb`，回退目录为绝对路径）
     * @param fileCount 包内含的 APK 数量（>1 表示 Split APK）
     */
    data class Success(val fileName: String, val location: String, val fileCount: Int) : ApkExtractResult
    /** 设备未返回任何安装包路径 */
    data object NoApkPath : ApkExtractResult
    data class Failure(val reason: String) : ApkExtractResult
}

/**
 * 解析 `pm path <pkg>` 输出为远程 APK 路径列表。
 * 输出形如 `package:/data/app/~~x==/com.foo-y==/base.apk`，base 在前、split 在后。
 */
fun parseApkPaths(output: String): List<String> =
    output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.isNotEmpty() && it.endsWith(".apk", ignoreCase = true) }
        .toList()

/** Split APK 打进 .apks（zip）时的条目名：保留设备端原名（base.apk / split_xxx.apk） */
fun apkEntryName(remotePath: String, index: Int): String {
    val raw = remotePath.substringAfterLast('/').ifBlank { "split$index.apk" }
    return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

// ── ADB 仓库 ──
@Singleton
class AdbRepository @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val usbAdbRepository: UsbAdbRepository,
    @ApplicationContext private val appContext: Context
) {
    private val manager: WearAdbConnectionManager by lazy {
        WearAdbConnectionManager.getInstance(appContext)
    }

    /** 应用名 / 图标解析器（app_process + dex）。 */
    private val appInfo by lazy { AppInfoResolver(appContext) }

    /** 当前设备的序列号缓存，供 UI 侧同步读缓存用（连接成功后写入）。 */
    @Volatile
    var appInfoSerialOverride: String? = null
        private set

    /** 记录当前设备序列号（连接成功 / 加载应用列表时调用）。 */
    suspend fun rememberAppInfoSerial() {
        val s = currentSerial()
        if (s.isNotEmpty() && s != "unknown") appInfoSerialOverride = s
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceBanner = MutableStateFlow("")
    val deviceBanner: StateFlow<String> = _deviceBanner.asStateFlow()

    val devices: Flow<List<SavedDevice>> = deviceRepository.devices

    // ── NSD 发现 ──
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val nsdManager by lazy {
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private val discovered = mutableListOf<DiscoveredDevice>()

    companion object {
        private const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
        private const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."

        /** 心跳间隔与超时：空闲连接不保活会被 Wi-Fi 省电/NAT 静默掐断 */
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
        private const val HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val HEARTBEAT_MAX_FAILURES = 2
        private const val MAX_RECONNECT_ATTEMPTS = 6

        /** 指数退避：baseMs * 2^attempt + jitter */
        private fun exponentialBackoff(attempt: Int, baseMs: Long = 500L, maxMs: Long = 10_000L): Long {
            val exp = baseMs * (1L shl (attempt - 1))
            val jitter = Random.nextLong(0, exp / 4 + 1)
            return (exp + jitter).coerceAtMost(maxMs)
        }
    }

    // ── 连接互斥锁，防止并发 connect 调用产生连接风暴 ──
    private val connectMutex = Mutex()

    // ── 保活与自动重连 ──
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var userRequestedDisconnect = false
    @Volatile private var lastConnectedHost: String? = null
    @Volatile private var lastConnectedPort = 0
    @Volatile private var lastConnectedUseTls = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun startDiscovery() {
        WearAdbLogger.i("AdbRepo", "开始NSD设备发现")
        // 先停掉旧的 NSD 扫描，再重新启动
        try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { pairingListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        _isDiscovering.value = true
        discovered.clear()
        _discoveredDevices.value = emptyList()

        discoveryListener = createNsdListener(SERVICE_TYPE_CONNECT, isPairing = false)
        pairingListener = createNsdListener(SERVICE_TYPE_PAIRING, isPairing = true)

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopDiscovery() {
        WearAdbLogger.i("AdbRepo", "停止NSD设备发现")
        _isDiscovering.value = false
        try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { pairingListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discoveryListener = null
        pairingListener = null
    }

    private fun createNsdListener(serviceType: String, isPairing: Boolean): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                android.util.Log.w("AdbRepo", "NSD discovery failed for $serviceType: error $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                        android.util.Log.w("AdbRepo", "NSD resolve failed: error $errorCode")
                    }
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = s.host?.hostAddress ?: return
                        val port = s.port
                        val device = DiscoveredDevice(
                            name = s.serviceName,
                            host = host,
                            port = port,
                            isPairing = isPairing
                        )
                        synchronized(discovered) {
                            discovered.removeAll { it.host == host && it.port == port }
                            discovered.add(device)
                            _discoveredDevices.value = discovered.toList()
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(discovered) {
                    discovered.removeAll { it.name == service.serviceName }
                    _discoveredDevices.value = discovered.toList()
                }
            }
        }
    }

    // ── 配对 ──

    /**
     * 配对成功后解析设备的无线调试连接端口。
     *
     * 背景：配对端口（_adb-tls-pairing）与连接端口（_adb-tls-connect）是两个不同的随机端口，
     * 配对协议本身不会告知连接端口。实测（24031PN0DC / HyperOS）设备端 mdns 广播在配对完成后
     * 有 ~10s 空窗期（配对服务注销后连接服务才重新应答），因此采用多轮发现重试。
     *
     * @return 连接端口；解析失败返回 null（调用方回退手动连接流程）
     */
    private suspend fun resolveConnectPort(host: String, maxRounds: Int = 4, roundTimeoutMs: Long = 5_000L, excludePorts: Set<Int> = emptySet()): Int? {
        repeat(maxRounds) { round ->
            // 先查扫描页缓存（发现结果可能早于本轮产生）；已试过的过期端口跳过
            discovered.firstOrNull { !it.isPairing && it.host == host && it.port !in excludePorts }?.let {
                WearAdbLogger.i("AdbRepo", "配对后从扫描结果解析连接端口(第${round + 1}轮): $host -> ${it.port}")
                return it.port
            }
            if (_isDiscovering.value) {
                // NSD 不允许并发同类型发现，本轮只等扫描页产出结果
                val deadline = System.currentTimeMillis() + roundTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    discovered.firstOrNull { !it.isPairing && it.host == host && it.port !in excludePorts }?.let {
                        WearAdbLogger.i("AdbRepo", "配对后从扫描结果解析连接端口(第${round + 1}轮): $host -> ${it.port}")
                        return it.port
                    }
                    delay(300)
                }
            } else {
                val port = discoverConnectPortOnce(host, roundTimeoutMs, excludePorts)
                if (port != null) {
                    WearAdbLogger.i("AdbRepo", "配对后解析连接端口(第${round + 1}轮): $host -> $port")
                    return port
                }
                WearAdbLogger.w("AdbRepo", "配对后解析连接端口(第${round + 1}轮): $host -> 未发现")
            }
            if (round < maxRounds - 1) delay(1_000)
        }
        WearAdbLogger.w("AdbRepo", "配对后解析连接端口失败（$maxRounds 轮未发现 $host）")
        return null
    }

    /** 单轮 _adb-tls-connect._tcp. mDNS 发现，只接受与配对主机一致的服务（防多设备局域网连错）；已试过的过期端口跳过。 */
    private suspend fun discoverConnectPortOnce(host: String, timeoutMs: Long, excludePorts: Set<Int> = emptySet()): Int? = withContext(Dispatchers.IO) {
        val resolved = CompletableDeferred<Int?>()
        var listener: NsdManager.DiscoveryListener? = null
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (resolved.isActive) resolved.complete(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}

            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {}
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val h = s.host?.hostAddress ?: return
                        // 只接受与配对主机一致的服务（防多设备连错），且排除已试过的过期端口
                        if (h == host && s.port !in excludePorts && resolved.isActive) resolved.complete(s.port)
                    }
                })
            }
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            WearAdbLogger.w("AdbRepo", "解析连接端口失败: discover 异常 ${e.message}")
            return@withContext null
        }
        val port = try {
            withTimeoutOrNull(timeoutMs) { resolved.await() }
        } finally {
            try { listener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        }
        port
    }

    suspend fun pair(host: String, port: Int, code: String): PairingResult = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var lastException: Exception? = null
        WearAdbLogger.i("AdbRepo", "配对开始: host=$host, port=$port")
        for (attempt in 1..maxRetries) {
            try {
                android.util.Log.d("AdbRepo", "pair() attempt $attempt/$maxRetries: host=$host, port=$port, code=${code.length}chars")
                _connectionState.value = ConnectionState.AUTHENTICATING
                manager.setHostAddress(host)
                val success = manager.pair(port, code)
                android.util.Log.d("AdbRepo", "pair() attempt $attempt result: success=$success")
                _connectionState.value = ConnectionState.DISCONNECTED
                if (success) {
                    WearAdbLogger.i("AdbRepo", "配对成功: host=$host, port=$port")
                    // 连接端口解析 + 自动连接全部放后台协程（repoScope，独立于 UI 生命周期）：
                    // ① 设备端 mdns 广播在配对完成后有 ~10s 空窗期，需多轮重试；
                    // ② 之前在 VM 的 viewModelScope 里连，界面切走会掐断连接（实测 "Job was cancelled"）；
                    // ③ mDNS 解析到的端口可能过期（无线调试重启后端口变更，系统缓存仍回旧记录，
                    //    实测 43995 已失效、真实端口 43859）→ 直连失败就换端口重试
                    repoScope.launch {
                        val triedPorts = mutableSetOf<Int>()
                        var connected = false
                        for (attempt in 1..3) {
                            val connectPort = resolveConnectPort(host, excludePorts = triedPorts)
                                ?: break
                            WearAdbLogger.i("AdbRepo", "配对成功后自动连接(第${attempt}次尝试): $host:$connectPort")
                            connect(host, connectPort, useTls = true, allowMdnsFallback = false)
                            if (_connectionState.value == ConnectionState.CONNECTED) {
                                connected = true
                                break
                            }
                            triedPorts.add(connectPort)
                            // 清掉疑似过期的缓存项，逼下一轮解析拿设备当前广播的新端口
                            synchronized(discovered) {
                                discovered.removeAll { it.host == host && it.port == connectPort }
                            }
                        }
                        if (!connected) {
                            WearAdbLogger.w("AdbRepo", "配对后自动连接失败，回退手动连接")
                        }
                    }
                    return@withContext PairingResult(true, host, port, "配对成功")
                }
                WearAdbLogger.w("AdbRepo", "配对失败: host=$host, port=$port")
                return@withContext PairingResult(false, host, port, "配对失败")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                android.util.Log.w("AdbRepo", "pair() attempt $attempt/$maxRetries failed: ${e.javaClass.simpleName}: ${e.message}")
                if (attempt < maxRetries) {
                    val backoff = exponentialBackoff(attempt)
                    android.util.Log.d("AdbRepo", "pair() retry waiting ${backoff}ms")
                    kotlinx.coroutines.delay(backoff)
                }
            }
        }
        _connectionState.value = ConnectionState.ERROR
        WearAdbLogger.e("AdbRepo", "配对异常: ${lastException?.message}", lastException)
        PairingResult(false, host, port, "配对异常: ${lastException?.message ?: "未知错误"}")
    }

    // ── 连接 ──
    suspend fun connect(host: String, port: Int = 55555, useTls: Boolean = false, allowMdnsFallback: Boolean = true) = withContext(Dispatchers.IO) {
        // 互斥锁：防止并发连接请求堆叠产生连接风暴
        connectMutex.withLock {
            // 如果已经在连接或已连接，直接跳过
            if (_connectionState.value == ConnectionState.CONNECTING) {
                android.util.Log.w("AdbRepo", "connect() skipped: already CONNECTING to another target")
                return@withContext
            }
            if (_connectionState.value == ConnectionState.CONNECTED && manager.isConnected) {
                android.util.Log.w("AdbRepo", "connect() skipped: already CONNECTED")
                return@withContext
            }

            try {
                WearAdbLogger.i("AdbRepo", "连接开始: host=$host, port=$port, tls=$useTls")
                android.util.Log.d("AdbRepo", "connect() called: host=$host, port=$port, useTls=$useTls")
                _connectionState.value = ConnectionState.CONNECTING

                var success = false

                // 尝试 TLS 连接
                if (useTls) {
                    // 首选直连已知端口：AdbConnection 收到 A_STLS 会自动完成 TLS 升级（libadb-android 3.1.1 内建），
                    // 无需 connectTls 的 jmDNS 组播发现——后者在 HyperOS 上经常 5s 超时（实测 2026-09-14）
                    try {
                        android.util.Log.d("AdbRepo", "Connecting via TLS direct: host=$host, port=$port")
                        success = manager.connect(host, port)
                        android.util.Log.d("AdbRepo", "TLS direct result: $success")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("AdbRepo", "TLS direct exception: ${e.javaClass.simpleName}: ${e.message}")
                        success = false
                    }

                    // 直连失败（可能端口不对/未知）→ 回退 connectTls 的 jmDNS 自动发现
                    // （自动连接流程传 allowMdnsFallback=false：端口来自 mDNS，直连失败说明端口过期，
                    //  应重走解析拿新端口而不是白等 5s jmDNS 超时——实测 2026-09-14 16:38）
                    if (!success && allowMdnsFallback) {
                        try {
                            android.util.Log.d("AdbRepo", "Connecting via TLS mdns auto-discover...")
                            manager.setHostAddress(host)
                            success = manager.connectTls(appContext, 5000)
                            android.util.Log.d("AdbRepo", "TLS mdns result: $success")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("AdbRepo", "TLS exception: ${e.javaClass.simpleName}: ${e.message}")
                            success = false
                        }
                    }
                }

                // TLS 未成功则降级为普通 TCP（最多重试 3 次，指数退避）
                if (!success) {
                    val maxRetries = 3
                    for (attempt in 1..maxRetries) {
                        try {
                            android.util.Log.d("AdbRepo", "Connecting via TCP attempt $attempt/$maxRetries to $host:$port ...")
                            success = manager.connect(host, port)
                            android.util.Log.d("AdbRepo", "TCP attempt $attempt result: $success")
                            if (success) break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("AdbRepo", "TCP attempt $attempt/$maxRetries failed: ${e.javaClass.simpleName}: ${e.message}")
                            if (attempt < maxRetries) {
                                val backoff = exponentialBackoff(attempt)
                                android.util.Log.d("AdbRepo", "TCP retry waiting ${backoff}ms")
                                kotlinx.coroutines.delay(backoff)
                            }
                        }
                    }
                }

                android.util.Log.d("AdbRepo", "connect() final success=$success, manager.isConnected=${manager.isConnected}, setting state")
                if (success) {
                    WearAdbLogger.i("AdbRepo", "连接成功: $host:$port")
                    _connectionState.value = ConnectionState.CONNECTED
                    _deviceBanner.value = "Connected to $host:$port"

                    // 记录连接参数供自动重连使用，并启动心跳保活
                    lastConnectedHost = host
                    lastConnectedPort = port
                    lastConnectedUseTls = useTls
                    userRequestedDisconnect = false
                    acquireKeepAliveLocks()
                    startHeartbeatMonitor()

                    deviceRepository.saveDevice(
                        SavedDevice(
                            host = host,
                            name = _deviceBanner.value,
                            lastConnected = System.currentTimeMillis()
                        )
                    )
                    deviceRepository.saveLastHost(host)
                    deviceRepository.saveLastPort(port)
                } else {
                    WearAdbLogger.w("AdbRepo", "连接失败: $host:$port")
                    android.util.Log.w("AdbRepo", "connect() returned false")
                    _connectionState.value = ConnectionState.ERROR
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WearAdbLogger.e("AdbRepo", "连接异常: $host:$port - ${e.message}", e)
                android.util.Log.e("AdbRepo", "connect() exception: ${e.javaClass.simpleName}: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    suspend fun autoConnect() = withContext(Dispatchers.IO) {
        try {
            WearAdbLogger.i("AdbRepo", "自动连接开始")
            _connectionState.value = ConnectionState.CONNECTING
            val success = manager.autoConnect(appContext, 5000)
            if (success) {
                WearAdbLogger.i("AdbRepo", "自动连接成功")
                _connectionState.value = ConnectionState.CONNECTED
                _deviceBanner.value = "Auto-connected"
            } else {
                WearAdbLogger.w("AdbRepo", "自动连接失败")
                _connectionState.value = ConnectionState.ERROR
            }
        } catch (e: Exception) {
            WearAdbLogger.e("AdbRepo", "自动连接异常: ${e.message}", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnect() {
        WearAdbLogger.i("AdbRepo", "断开连接")
        // 用户主动断开：停止心跳与自动重连
        userRequestedDisconnect = true
        monitorJob?.cancel()
        monitorJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        releaseKeepAliveLocks()
        try { manager.disconnect() } catch (_: Exception) {}
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceBanner.value = ""
    }

    /**
     * 心跳监控：定期用一条轻量 exec 流做往返探测。
     * 库内部 socket 没开 TCP keepalive，空闲连接会被 Wi-Fi 省电/NAT 静默丢弃，
     * 且库的 openStream 在无响应时会无限期阻塞，因此探测包用 runInterruptible + withTimeout 兜底。
     */
    private fun startHeartbeatMonitor() {
        monitorJob?.cancel()
        monitorJob = repoScope.launch {
            var failures = 0
            while (isActive) {
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
                if (userRequestedDisconnect) return@launch
                if (_connectionState.value != ConnectionState.CONNECTED) return@launch

                // 库的连接线程已退出（mConnectionEstablished=false）→ 连接必然已断
                val conn = manager.getAdbConnection()
                if (conn == null || !conn.isConnectionEstablished) {
                    WearAdbLogger.w("AdbRepo", "心跳检测: 连接已失效 (connectionEstablished=${conn?.isConnectionEstablished})")
                    handleConnectionLost()
                    return@launch
                }

                // 应用层心跳：OPEN→OKAY 往返确认链路可用
                try {
                    withTimeout(HEARTBEAT_TIMEOUT_MS) {
                        runInterruptible(Dispatchers.IO) {
                            val stream = manager.openStream("exec:echo wearadb-heartbeat")
                            try { stream.close() } catch (_: Exception) {}
                        }
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    WearAdbLogger.w("AdbRepo", "心跳失败 $failures/$HEARTBEAT_MAX_FAILURES: ${e.javaClass.simpleName}: ${e.message}")
                    if (failures >= HEARTBEAT_MAX_FAILURES) {
                        handleConnectionLost()
                        return@launch
                    }
                }
            }
        }
    }

    /** 连接失效的统一入口：只允许从 CONNECTED 状态触发一次，避免心跳与命令线程重复触发 */
    private fun handleConnectionLost() {
        synchronized(this) {
            if (_connectionState.value != ConnectionState.CONNECTED) return
            WearAdbLogger.w("AdbRepo", "检测到连接断开")
            releaseKeepAliveLocks()
            try { manager.disconnect() } catch (_: Exception) {}
            _connectionState.value = ConnectionState.DISCONNECTED
            _deviceBanner.value = ""
        }
        if (!userRequestedDisconnect && lastConnectedHost != null && lastConnectedPort > 0) {
            scheduleReconnect()
        }
    }

    /** 断线自动重连：指数退避重试最近一次成功的连接参数 */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val host = lastConnectedHost ?: return
        val port = lastConnectedPort
        val useTls = lastConnectedUseTls
        reconnectJob = repoScope.launch {
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                if (userRequestedDisconnect) return@launch
                val backoff = exponentialBackoff(attempt, baseMs = 1_000L, maxMs = 15_000L)
                WearAdbLogger.i("AdbRepo", "自动重连 $attempt/$MAX_RECONNECT_ATTEMPTS: $host:$port, ${backoff}ms 后重试")
                kotlinx.coroutines.delay(backoff)
                if (userRequestedDisconnect || _connectionState.value == ConnectionState.CONNECTED) return@launch
                connect(host, port, useTls)
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    WearAdbLogger.i("AdbRepo", "自动重连成功: $host:$port")
                    return@launch
                }
            }
            WearAdbLogger.w("AdbRepo", "自动重连 $MAX_RECONNECT_ATTEMPTS 次后放弃，等待用户手动连接")
        }
    }

    /** 连接期间持有 WakeLock/WifiLock，防止控制器端息屏进 Doze 后网络被冻结 */
    @Suppress("DEPRECATION")
    private fun acquireKeepAliveLocks() {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wearadb:adb-conn").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire()
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wearadb:adb-wifi").apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
        } catch (e: Exception) {
            WearAdbLogger.w("AdbRepo", "获取保活锁失败: ${e.message}")
        }
    }

    private fun releaseKeepAliveLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    fun isConnected(): Boolean = manager.isConnected

    suspend fun getLastHost(): String = deviceRepository.getLastHost()
    suspend fun getLastPort(): Int = deviceRepository.getLastPort()

    suspend fun isWearOs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val chars = runSingleCommand("getprop ro.build.characteristics", 5000)
            android.util.Log.d("AdbRepo", "isWearOs() characteristics=$chars")
            chars.contains("watch", ignoreCase = true)
        } catch (e: Exception) {
            android.util.Log.w("AdbRepo", "isWearOs() check failed: ${e.message}")
            false
        }
    }

    suspend fun disableBluetoothAfterConnect() {
        try { runSingleCommand("svc bluetooth disable", 5000) } catch (_: Exception) {}
    }

    // ── 顺序执行多个命令，避免并发流冲突 ──
    private suspend fun runSequentialCommands(vararg commands: String, timeoutMs: Long = 15000): List<String> {
        return commands.map { runSingleCommand(it, timeoutMs) }
    }

    // ── 核心：执行命令（带重试）──
    private suspend fun runSingleCommand(command: String, timeoutMs: Long = 15000): String =
        withContext(Dispatchers.IO) {
            val maxRetries = 3
            var lastException: Exception? = null
            android.util.Log.d("AdbRepo", "runSingleCommand START: cmd=${command.take(80)} manager.isConnected=${manager.isConnected}")
            for (attempt in 1..maxRetries) {
                try {
                    android.util.Log.d("AdbRepo", "runSingleCommand: cmd=${command.take(80)}")
                    val endMarker = "__CMD_END_${System.nanoTime()}__"

                    var output = readUntilMarker(
                        openStream = { manager.openStream("exec:$command 2>&1") },
                        endMarker = "",
                        writeCommand = false,
                        timeoutMs = timeoutMs
                    )

                    if (output.isEmpty()) {
                        android.util.Log.d("AdbRepo", "runSingleCommand: exec empty, fallback to shell")
                        output = readUntilMarker(
                            openStream = { manager.openStream(LocalServices.SHELL) },
                            endMarker = endMarker,
                            writeCommand = true,
                            command = "$command; echo $endMarker",
                            timeoutMs = timeoutMs
                        )
                        output = output.lineSequence()
                            .filter { !it.contains(endMarker) }
                            .toList()
                            .dropLastWhile { it.trim().let { l -> l.endsWith("$") || l.endsWith("#") } }
                            .joinToString("\n")
                    }

                    android.util.Log.d("AdbRepo", "runSingleCommand: done, chars=${output.length}")
                    return@withContext output.trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    lastException = e
                    android.util.Log.w("AdbRepo", "runSingleCommand: attempt $attempt/$maxRetries failed: ${e.message}")
                    // 连接已失效（SocketException/Broken pipe 等）→ 标记断开并触发自动重连，不再空转重试
                    val conn = manager.getAdbConnection()
                    if (conn == null || !conn.isConnectionEstablished) {
                        android.util.Log.w("AdbRepo", "runSingleCommand: connection lost, triggering reconnect")
                        handleConnectionLost()
                        return@withContext ""
                    }
                    if (attempt < maxRetries) {
                        val backoff = exponentialBackoff(attempt)
                        android.util.Log.d("AdbRepo", "runSingleCommand retry waiting ${backoff}ms")
                        kotlinx.coroutines.delay(backoff)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AdbRepo", "runSingleCommand exception: ${e.message}", e)
                    return@withContext ""
                }
            }
            android.util.Log.e("AdbRepo", "runSingleCommand: all $maxRetries attempts failed", lastException)
            ""
        }

    /**
     * 通用流读取：打开流 → 可选写命令 → 读到标记或 EOF → 关闭。
     * Uses kotlinx.coroutines withTimeout for reliable timeout —
     * closing the stream unblocks the blocking read, no thread leak.
     */
    private suspend fun readUntilMarker(
        openStream: () -> AdbStream,
        endMarker: String,
        writeCommand: Boolean = false,
        command: String = "",
        timeoutMs: Long = 15000
    ): String {
        val stream = try {
            android.util.Log.d("AdbRepo", "readUntilMarker: opening stream...")
            openStream()
        } catch (e: Exception) {
            android.util.Log.e("AdbRepo", "readUntilMarker: FAILED to open stream: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        android.util.Log.d("AdbRepo", "readUntilMarker: stream opened, writeCommand=$writeCommand")
        try {
            return kotlinx.coroutines.withTimeout(timeoutMs) {
                if (writeCommand) {
                    val os = stream.openOutputStream()
                    os.write("$command\n".toByteArray())
                    os.flush()
                }

                val sb = StringBuilder()
                val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            sb.appendLine(line)
                            if (endMarker.isNotEmpty() && line.contains(endMarker)) break
                        }
                    } catch (_: Exception) {}
                }
                sb.toString()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Timeout expired — stream.close() below will unblock any pending read
            return ""
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    // ── 设备信息 ──
    suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        android.util.Log.d("AdbRepo", "getDeviceInfo() start")
        val raw = runSingleCommand(
            "echo ==PROPS==; getprop; " +
            "echo ==BATTERY==; dumpsys battery; " +
            "cat /sys/class/power_supply/battery/uevent 2>/dev/null; " +
            "cat /sys/class/power_supply/Battery/uevent 2>/dev/null; " +
            "dumpsys batterystats 2>/dev/null | grep -i 'charge_full\\|charge_full_design\\|capacity'; " +
            "echo ==DISPLAY==; wm size; wm density; " +
            "echo ==MEM==; cat /proc/meminfo | head -3; " +
            "echo ==UPTIME==; uptime; " +
            "echo ==STORAGE==; df -h /data 2>/dev/null || df -h /storage/emulated 2>/dev/null || df -h 2>/dev/null | head -5"
        , 30000)

        // 清理 shell 回显和 prompt
        val output = cleanShellOutput(raw)
        android.util.Log.d("AdbRepo", "getDeviceInfo() raw length=${raw.length}, cleaned length=${output.length}")
        android.util.Log.d("AdbRepo", "getDeviceInfo() raw first300=${raw.take(300)}")
        android.util.Log.d("AdbRepo", "getDeviceInfo() cleaned first300=${output.take(300)}")
        val result = AdbOutputParser.parseDeviceInfo(output)
        val (storageTotal, storageUsed, storageFree) = AdbOutputParser.parseStorageInfo(output)
        android.util.Log.d("AdbRepo", "getDeviceInfo() parsed: model=${result.model}, storage=$storageTotal")
        var info = result.copy(storageTotal = storageTotal, storageUsed = storageUsed, storageFree = storageFree)

        // root 专属：循环次数 + UFS 闪存寿命 + 电池容量（仅设备已 root 时 su 可用；非 root / 超时则静默跳过）
        //   设计容量 / 当前满容量对 shell 身份 Permission denied，仅 root 可读；健康度% 依赖二者，故在此合并时计算。
        try {
            val rootOut = runSingleCommand(
                "echo ==EXTRA==; su -c 'echo CYCLE; cat /sys/class/power_supply/battery/cycle_count 2>/dev/null; " +
                "D=\$(find /sys/devices -type d -name health_descriptor 2>/dev/null | head -1); " +
                "echo UFS; echo \$D; cat \$D/life_time_estimation_a 2>/dev/null; echo SEP; cat \$D/life_time_estimation_b 2>/dev/null; " +
                "echo CFULL; cat /sys/class/power_supply/battery/charge_full 2>/dev/null; " +
                "echo CDESIGN; cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null'",
                8000
            )
            AdbOutputParser.parseRootExtras(rootOut)?.let { ex ->
                val currentMah = if (ex.chargeFull > 0) (ex.chargeFull / 1000).toInt() else info.batteryCurrentCapacity
                val designMah = if (ex.chargeFullDesign > 0) (ex.chargeFullDesign / 1000).toInt() else info.batteryDesignCapacity
                val healthPct = if (designMah > 0 && currentMah > 0) currentMah.toFloat() / designMah * 100f else 0f
                info = info.copy(
                    batteryCycleCount = ex.cycleCount,
                    flashLifeA = ex.flashLifeA,
                    flashLifeB = ex.flashLifeB,
                    batteryCurrentCapacity = currentMah,
                    batteryDesignCapacity = designMah,
                    batteryHealthPct = healthPct
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("AdbRepo", "getDeviceInfo() root extras failed: ${e.message}")
        }
        info
    }

    /**
     * 从 ADB shell 输出中提取标记之间的内容。
     * 去掉所有 prompt 行和命令回显行。
     */
    private fun cleanShellOutput(raw: String): String {
        val markers = listOf("==PROPS==", "==BATTERY==", "==DISPLAY==", "==MEM==", "==UPTIME==", "==STORAGE==", "==EXTRA==")
        val lines = raw.lines()
        val result = mutableListOf<String>()
        var collecting = false

        for (line in lines) {
            val trimmed = line.trim()
            // 遇到标记行：开始收集
            if (markers.any { trimmed.startsWith(it) }) {
                collecting = true
                result.add(line)
                continue
            }
            if (!collecting) continue
            // 跳过 prompt 行（以 $ 或 # 结尾，且不含 = 号）
            if ((trimmed.endsWith("$") || trimmed.endsWith("#")) && !trimmed.contains("=")) continue
            // 跳过命令回显行
            if (trimmed.contains("echo ==")) continue
            result.add(line)
        }
        return result.joinToString("\n")
    }

    // ── 应用管理 ──
    suspend fun getInstalledPackages(): List<AppEntry> = withContext(Dispatchers.IO) {
        // 合并为单条命令，避免多次开流导致输出截断（8KB限制）
        // -d = disabled packages，用于标记 isEnabled
        android.util.Log.d("AdbRepo", "getInstalledPackages() START")
        val combined = runSingleCommand(
            "echo ==FULL==; pm list packages -f; echo ==SYSTEM==; pm list packages -s; echo ==THIRD==; pm list packages -3; echo ==DISABLED==; pm list packages -d"
        )
        android.util.Log.d("AdbRepo", "getInstalledPackages() raw length=${combined.length}, first500=${combined.take(500)}")
        val sections = combined.split(Regex("==FULL==|==SYSTEM==|==THIRD==|==DISABLED=="))
        val fullOutput = sections.getOrElse(1) { "" }.trim()
        val systemOutput = sections.getOrElse(2) { "" }.trim()
        val thirdPartyOutput = sections.getOrElse(3) { "" }.trim()
        val disabledOutput = sections.getOrElse(4) { "" }.trim()
        val systemPkgs = systemOutput.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
        val thirdPartyPkgs = thirdPartyOutput.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
        val disabledPkgs = disabledOutput.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
        android.util.Log.d("AdbRepo", "getInstalledPackages() full=${fullOutput.length}, system=${systemPkgs.size}, thirdParty=${thirdPartyPkgs.size}, disabled=${disabledPkgs.size}")
        val result = AdbOutputParser.parsePackageListWithFilter(fullOutput, systemPkgs, thirdPartyPkgs, disabledPkgs)
        val enabledCount = result.count { it.isEnabled }
        val disabledCount = result.count { !it.isEnabled }
        android.util.Log.d("AdbRepo", "getInstalledPackages() parsed ${result.size} apps, system=${result.count { it.isSystem }}, enabled=$enabledCount, disabled=$disabledCount")
        result
    }

    // ── 应用名 / 图标（app_process + dex）──
    /** 当前连接设备的序列号，用于按设备隔离缓存。 */
    private suspend fun currentSerial(): String =
        runSingleCommand("getprop ro.serialno").trim().ifEmpty {
            runSingleCommand("settings get secure android_id").trim().ifEmpty { "unknown" }
        }

    /**
     * 批量解析应用名 + 图标。内部整批下发（app_process 启动开销大），
     * 已缓存的不重复解析。
     *
     * @param packages 待解析包名；调用方一般传入全量列表，本方法自行跳过已缓存的。
     * @param force 忽略磁盘缓存强制重新解析。
     * @param onProgress (已完成, 总数)
     */
    suspend fun resolveAppInfo(
        packages: List<String>,
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<String, AppInfoResolver.AppInfo> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptyMap()
        val serial = currentSerial()
        if (serial.isNotEmpty() && serial != "unknown") appInfoSerialOverride = serial
        if (force) appInfo.clearDisk(serial)

        val pending = packages.filter { force || !appInfo.hasCache(serial, it) }
        if (pending.isEmpty()) {
            Log.d("AdbRepo", "resolveAppInfo: 全部命中缓存（${packages.size} 个）")
            return@withContext emptyMap()
        }

        appInfo.resolve(
            serial = serial,
            packages = pending,
            exec = { cmd -> runSingleCommand(cmd, 60000).ifBlank { null } },
            push = { local, remote -> pushFile(File(local), remote).contains("成功") },
            pull = { remote -> pullFile(remote).let { if (it.success) it.data else null } },
            onProgress = onProgress
        )
    }

    /** 已缓存的图标文件（UI 可直接喂给图片加载器）。 */
    fun cachedAppIconFile(pkg: String): File? =
        appInfoSerialOverride?.let { appInfo.cachedIconFile(it, pkg) }

    // ── 应用名 / 图标（USB 通道版，路由到 UsbAdbRepository）──
    /** USB 通道的当前序列号，用于按设备隔离缓存。 */
    private suspend fun currentSerialUsb(): String =
        usbAdbRepository.executeCommand("getprop ro.serialno").trim().ifEmpty {
            usbAdbRepository.executeCommand("settings get secure android_id").trim().ifEmpty { "unknown" }
        }

    /**
     * 与 [resolveAppInfo] 逻辑一致，但命令执行 / 推送 / 拉取全部走 USB ADB。
     * 用于「手机端」等通过 USB 有线连接上来的设备——这些设备此前因
     * [resolveAppInfo] 仅走无线通道而永远拿不到名称与图标。
     */
    suspend fun resolveAppInfoUsb(
        packages: List<String>,
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<String, AppInfoResolver.AppInfo> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptyMap()
        val serial = currentSerialUsb()
        if (serial.isNotEmpty() && serial != "unknown") appInfoSerialOverride = serial
        if (force) appInfo.clearDisk(serial)

        val pending = packages.filter { force || !appInfo.hasCache(serial, it) }
        if (pending.isEmpty()) {
            Log.d("AdbRepo", "resolveAppInfoUsb: 全部命中缓存（${packages.size} 个）")
            return@withContext emptyMap()
        }

        appInfo.resolve(
            serial = serial,
            packages = pending,
            exec = { cmd -> usbAdbRepository.executeCommand(cmd, 60000).ifBlank { null } },
            push = { local, remote -> usbAdbRepository.pushFile(File(local), remote).contains("成功") },
            pull = { remote -> usbAdbRepository.pullFile(remote).let { if (it.success) it.data else null } },
            onProgress = onProgress
        )
    }

    /** 已缓存的应用名。 */
    fun cachedAppLabel(pkg: String): String? =
        appInfoSerialOverride?.let { appInfo.cachedLabel(it, pkg) }

    fun clearAppInfoMemoryCache() = appInfo.clearMemory()

    suspend fun uninstallApp(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "卸载应用: pkg=$pkg")
        val result = runSingleCommand("pm uninstall $pkg")
        WearAdbLogger.i("AdbRepo", "卸载结果: $pkg - $result")
        result
    }

    suspend fun clearAppData(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "清除应用数据: pkg=$pkg")
        val result = runSingleCommand("pm clear $pkg")
        WearAdbLogger.i("AdbRepo", "清除数据结果: $pkg - $result")
        result
    }

    suspend fun forceStopApp(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "强制停止应用: pkg=$pkg")
        val result = runSingleCommand("am force-stop $pkg")
        WearAdbLogger.i("AdbRepo", "强制停止结果: $pkg - $result")
        result
    }

    suspend fun disableApp(pkg: String): String = withContext(Dispatchers.IO) {
        android.util.Log.d("AdbRepo", "disableApp() pkg=$pkg")
        val result = runSingleCommand("pm disable-user $pkg")
        android.util.Log.d("AdbRepo", "disableApp() result: $result")
        result
    }

    suspend fun enableApp(pkg: String): String = withContext(Dispatchers.IO) {
        android.util.Log.d("AdbRepo", "enableApp() pkg=$pkg")
        val result = runSingleCommand("pm enable $pkg")
        android.util.Log.d("AdbRepo", "enableApp() result: $result")
        result
    }

    /** 卸载但保留数据（pm uninstall -k：保留 /data/data 与 /sdcard 下的应用数据） */
    suspend fun uninstallAppKeepData(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "卸载(保留数据): pkg=$pkg")
        val result = runSingleCommand("pm uninstall -k $pkg")
        WearAdbLogger.i("AdbRepo", "卸载(保留数据)结果: $pkg - $result")
        result
    }

    /** `pm path <pkg>` → 远程 APK 路径列表（base 在前，split 在后）；无输出返回空列表 */
    suspend fun packageApkPaths(pkg: String): List<String> = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "查询安装包路径: pkg=$pkg")
        parseApkPaths(runSingleCommand("pm path $pkg"))
    }

    /**
     * 远程文件字节数（`stat -c %s`）。
     * 取不到时返回 -1，调用方据此退化为"未知总量的进度显示"而不是误报 0。
     */
    suspend fun remoteFileSize(path: String): Long = withContext(Dispatchers.IO) {
        runSingleCommand("stat -c %s '$path'", 8000).trim().toLongOrNull() ?: -1L
    }

    /**
     * 流式拉取远程文件到 [sink]（SYNC RECV，DATA 块直接写出）。
     *
     * 与 [pullFile] 的关键区别：**不把文件缓存在内存里**——pullFile 用 ByteArrayOutputStream
     * 累积整份数据，提取 250MB 级 APK 时扩容单次申请 256MB 直接 OOM 崩溃（真机已复现）。
     * 这里内存占用固定（一个 64KB 数据块）。
     *
     * @return true 表示收到 DONE（数据完整）
     */
    suspend fun pullTo(remotePath: String, sink: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "流式拉取: remotePath=$remotePath")
        var stream: AdbStream? = null
        try {
            stream = manager.openStream(LocalServices.SYNC)
            val os = stream.openOutputStream()
            val inputStream = stream.openInputStream()

            val pathBytes = remotePath.toByteArray()
            val recvBuf = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            recvBuf.putInt(0x56434552) // RECV
            recvBuf.putInt(pathBytes.size)
            recvBuf.put(pathBytes)
            os.write(recvBuf.array())
            os.flush()

            val headerBuf = ByteArray(8)
            val dataBuf = ByteArray(64 * 1024)
            var totalWritten = 0L
            var ok = false
            while (true) {
                val read = inputStream.read(headerBuf)
                if (read < 8) break
                val cmd = littleEndianToInt(headerBuf, 0)
                val size = littleEndianToInt(headerBuf, 4)
                when (cmd) {
                    0x41544144 -> { // DATA
                        var remaining = size
                        while (remaining > 0) {
                            val n = inputStream.read(dataBuf, 0, minOf(remaining, dataBuf.size))
                            if (n <= 0) break
                            sink.write(dataBuf, 0, n)
                            totalWritten += n
                            remaining -= n
                        }
                    }
                    0x454e4f44 -> { ok = true; break }   // DONE
                    0x4c494146 -> break                  // FAIL
                    else -> break
                }
            }
            sink.flush()
            android.util.Log.d("AdbRepo", "pullTo: written=$totalWritten, ok=$ok")
            ok
        } catch (e: Exception) {
            WearAdbLogger.e("AdbRepo", "流式拉取异常: $remotePath - ${e.message}", e)
            false
        } finally {
            try { stream?.close() } catch (_: Exception) {}
        }
    }

    // ── 安装 APK ──
    suspend fun installApk(apkData: ByteArray): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "安装APK(ByteArray): size=${apkData.size}")
        android.util.Log.d("AdbRepo", "installApk(ByteArray): size=${apkData.size}")
        try {
            val tmpPath = "/data/local/tmp/_wearadb_install_${System.currentTimeMillis()}.apk"
            // 1. 推送 APK 到临时目录
            android.util.Log.d("AdbRepo", "installApk(ByteArray): pushing to $tmpPath")
            val pushResult = pushFile(apkData, tmpPath)
            android.util.Log.d("AdbRepo", "installApk(ByteArray): pushResult=$pushResult")
            if (!pushResult.contains("成功")) return@withContext "推送失败: $pushResult"
            // 2. 执行安装
            android.util.Log.d("AdbRepo", "installApk(ByteArray): running pm install")
            val installResult = runSingleCommand("pm install -r $tmpPath", 60000)
            android.util.Log.d("AdbRepo", "installApk(ByteArray): installResult=$installResult")
            // 3. 清理临时文件
            runSingleCommand("rm -f $tmpPath", 5000)
            // 4. 返回结果
            val clean = installResult.trim()
            when {
                clean.contains("Success") -> {
                    WearAdbLogger.i("AdbRepo", "APK安装成功(ByteArray)")
                    "安装成功"
                }
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            WearAdbLogger.e("AdbRepo", "APK安装异常(ByteArray): ${e.message}", e)
            android.util.Log.e("AdbRepo", "installApk(ByteArray) exception", e)
            "安装异常: ${e.message}"
        }
    }

    // ── 安装 Split APK (.apks) ──
    suspend fun installSplitApk(apkFiles: List<Pair<String, ByteArray>>): String = withContext(Dispatchers.IO) {
        android.util.Log.d("AdbRepo", "installSplitApk: ${apkFiles.size} files")
        val tmpDir = "/data/local/tmp/_wearadb_split_${System.currentTimeMillis()}"
        try {
            // 1. 创建临时目录
            runSingleCommand("mkdir -p $tmpDir", 5000)
            android.util.Log.d("AdbRepo", "installSplitApk: tmpDir=$tmpDir")
            // 2. 推送所有 split APK 到设备
            for ((name, data) in apkFiles) {
                // 用安全文件名，避免特殊字符导致 shell 解析异常
                val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val remotePath = "$tmpDir/$safeName"
                android.util.Log.d("AdbRepo", "installSplitApk: pushing $safeName (${data.size} bytes)")
                val pushResult = pushFile(data, remotePath)
                android.util.Log.d("AdbRepo", "installSplitApk: push $safeName result=$pushResult")
                if (!pushResult.contains("成功")) {
                    runSingleCommand("rm -rf $tmpDir", 5000)
                    return@withContext "推送失败 ($name): $pushResult"
                }
            }
            // 3. 创建安装会话（不使用 -S 参数，兼容更多设备）
            val createResult = runSingleCommand("pm install-create", 30000)
            android.util.Log.d("AdbRepo", "installSplitApk: createResult='$createResult'")
            val sessionId = Regex("sessionId\\s*[=:]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(createResult)?.groupValues?.get(1)
                ?: Regex("(\\d+)").find(createResult.trim())?.groupValues?.get(1)
            if (sessionId == null) {
                runSingleCommand("rm -rf $tmpDir", 5000)
                return@withContext "创建会话失败: ${createResult.trim()}"
            }
            // 4. 写入每个 split APK（用安全文件名）
            for ((name, _) in apkFiles) {
                val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val remotePath = "$tmpDir/$safeName"
                val writeResult = runSingleCommand("pm install-write $sessionId $safeName $remotePath", 60000)
                android.util.Log.d("AdbRepo", "installSplitApk: write $safeName result='$writeResult'")
                if (!writeResult.contains("Success") && writeResult.trim().isNotEmpty() &&
                    !writeResult.contains("success", ignoreCase = true)) {
                    runSingleCommand("pm install-abandon $sessionId", 5000)
                    runSingleCommand("rm -rf $tmpDir", 5000)
                    return@withContext "写入失败 ($name): ${writeResult.trim()}"
                }
            }
            // 5. 提交安装
            val commitResult = runSingleCommand("pm install-commit $sessionId", 60000)
            android.util.Log.d("AdbRepo", "installSplitApk: commitResult='$commitResult'")
            // 6. 清理临时文件
            runSingleCommand("rm -rf $tmpDir", 5000)
            // 7. 返回结果
            val clean = commitResult.trim()
            when {
                clean.contains("Success") -> "Split APK 安装成功 (${apkFiles.size} 个文件)"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            runSingleCommand("rm -rf $tmpDir", 5000)
            "安装异常: ${e.message}"
        }
    }
    // ── 安装 APK（File 版，避免 OOM）──
    suspend fun installApk(apkFile: File): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "安装APK(File): ${apkFile.name}, size=${apkFile.length()}")
        android.util.Log.d("AdbRepo", "installApk(File): ${apkFile.name}, size=${apkFile.length()}")
        try {
            val tmpPath = "/data/local/tmp/_wearadb_install_${System.currentTimeMillis()}.apk"
            android.util.Log.d("AdbRepo", "installApk(File): pushing to $tmpPath")
            val pushResult = pushFile(apkFile, tmpPath)
            android.util.Log.d("AdbRepo", "installApk(File): pushResult=$pushResult")
            if (!pushResult.contains("成功")) return@withContext "推送失败: $pushResult"
            android.util.Log.d("AdbRepo", "installApk(File): running pm install")
            val installResult = runSingleCommand("pm install -r $tmpPath", 60000)
            android.util.Log.d("AdbRepo", "installApk(File): installResult=$installResult")
            runSingleCommand("rm -f $tmpPath", 5000)
            val clean = installResult.trim()
            when {
                clean.contains("Success") -> {
                    WearAdbLogger.i("AdbRepo", "APK安装成功(File): ${apkFile.name}")
                    "安装成功"
                }
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            WearAdbLogger.e("AdbRepo", "APK安装异常(File): ${apkFile.name} - ${e.message}", e)
            android.util.Log.e("AdbRepo", "installApk(File) exception", e)
            "安装异常: ${e.message}"
        }
    }

    // ── 安装 Split APK（File 版，避免 OOM）──
    suspend fun installSplitApkFiles(apkFiles: List<Pair<String, File>>): String = withContext(Dispatchers.IO) {
        val tmpDir = "/data/local/tmp/_wearadb_split_${System.currentTimeMillis()}"
        android.util.Log.d("AdbRepo", "installSplitApkFiles: ${apkFiles.size} files, tmpDir=$tmpDir")
        try {
            runSingleCommand("mkdir -p $tmpDir", 5000)
            for ((name, file) in apkFiles) {
                val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val remotePath = "$tmpDir/$safeName"
                android.util.Log.d("AdbRepo", "installSplitApkFiles: pushing $safeName (${file.length()} bytes)")
                val pushResult = pushFile(file, remotePath)
                android.util.Log.d("AdbRepo", "installSplitApkFiles: push result: $pushResult")
                if (!pushResult.contains("成功")) {
                    runSingleCommand("rm -rf $tmpDir", 5000)
                    return@withContext "推送失败 ($name): $pushResult"
                }
            }
            // 不使用 -S 参数，兼容更多设备
            val createResult = runSingleCommand("pm install-create", 30000)
            android.util.Log.d("AdbRepo", "installSplitApkFiles: createResult='$createResult'")
            val sessionId = Regex("sessionId\\s*[=:]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(createResult)?.groupValues?.get(1)
                ?: Regex("(\\d+)").find(createResult.trim())?.groupValues?.get(1)
            if (sessionId == null) {
                runSingleCommand("rm -rf $tmpDir", 5000)
                return@withContext "创建会话失败: ${createResult.trim()}"
            }
            for ((name, _) in apkFiles) {
                val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val remotePath = "$tmpDir/$safeName"
                val writeResult = runSingleCommand("pm install-write $sessionId $safeName $remotePath", 60000)
                android.util.Log.d("AdbRepo", "installSplitApkFiles: write $safeName result='$writeResult'")
                if (!writeResult.contains("Success") && writeResult.trim().isNotEmpty() &&
                    !writeResult.contains("success", ignoreCase = true)) {
                    runSingleCommand("pm install-abandon $sessionId", 5000)
                    runSingleCommand("rm -rf $tmpDir", 5000)
                    return@withContext "写入失败 ($name): ${writeResult.trim()}"
                }
            }
            val commitResult = runSingleCommand("pm install-commit $sessionId", 60000)
            android.util.Log.d("AdbRepo", "installSplitApkFiles: commitResult='$commitResult'")
            runSingleCommand("rm -rf $tmpDir", 5000)
            val clean = commitResult.trim()
            when {
                clean.contains("Success") -> "Split APK 安装成功 (${apkFiles.size} 个文件)"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            runSingleCommand("rm -rf $tmpDir", 5000)
            "安装异常: ${e.message}"
        }
    }

    // ── .apks 流式安装（逐个解压+推送，避免 OOM）──
    suspend fun installSplitApkFromApksFile(apksFile: File): String = withContext(Dispatchers.IO) {
        val tmpDir = "/data/local/tmp/_wearadb_split_${System.currentTimeMillis()}"
        try {
            // 1. 创建临时目录
            runSingleCommand("mkdir -p $tmpDir", 5000)

            // 2. 创建安装会话
            val createResult = runSingleCommand("pm install-create", 30000)
            android.util.Log.d("AdbRepo", "installApks: createResult='$createResult'")
            val sessionId = Regex("sessionId\\s*[=:]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(createResult)?.groupValues?.get(1)
                ?: Regex("(\\d+)").find(createResult.trim())?.groupValues?.get(1)
            if (sessionId == null) {
                runSingleCommand("rm -rf $tmpDir", 5000)
                return@withContext "创建安装会话失败: ${createResult.trim()}"
            }

            // 3. 逐个解压 APK → 推送 → 写入安装会话（不同时加载全部到内存）
            var count = 0
            var totalSize = 0L
            java.util.zip.ZipInputStream(apksFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        val safeName = entry.name.substringAfterLast('/')
                            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val tmpApk = File(apksFile.parentFile, "wearadb_tmp_$safeName")
                        try {
                            // 解压到临时文件
                            tmpApk.outputStream().use { out -> zip.copyTo(out) }
                            val size = tmpApk.length()
                            if (size <= 0) {
                                android.util.Log.w("AdbRepo", "installApks: skipping empty $safeName")
                                entry = zip.nextEntry
                                continue
                            }
                            android.util.Log.d("AdbRepo", "installApks: extracted $safeName ($size bytes)")

                            // 推送到设备
                            val remotePath = "$tmpDir/$safeName"
                            val pushResult = pushFile(tmpApk, remotePath)
                            if (!pushResult.contains("成功")) {
                                android.util.Log.e("AdbRepo", "installApks: push failed for $safeName: $pushResult")
                                runSingleCommand("pm install-abandon $sessionId", 5000)
                                runSingleCommand("rm -rf $tmpDir", 5000)
                                return@withContext "推送失败 ($safeName): $pushResult"
                            }

                            // 写入安装会话
                            val writeResult = runSingleCommand("pm install-write $sessionId $safeName $remotePath", 60000)
                            android.util.Log.d("AdbRepo", "installApks: write $safeName result='$writeResult'")
                            if (!writeResult.contains("Success") && writeResult.trim().isNotEmpty() &&
                                !writeResult.contains("success", ignoreCase = true)) {
                                runSingleCommand("pm install-abandon $sessionId", 5000)
                                runSingleCommand("rm -rf $tmpDir", 5000)
                                return@withContext "写入失败 ($safeName): ${writeResult.trim()}"
                            }

                            count++
                            totalSize += size
                        } finally {
                            try { tmpApk.delete() } catch (_: Exception) {}
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            if (count == 0) {
                runSingleCommand("pm install-abandon $sessionId", 5000)
                runSingleCommand("rm -rf $tmpDir", 5000)
                return@withContext ".apks 中未找到有效 APK 文件"
            }

            // 4. 提交安装
            android.util.Log.d("AdbRepo", "installApks: committing $count APKs, totalSize=$totalSize")
            val commitResult = runSingleCommand("pm install-commit $sessionId", 120000)
            android.util.Log.d("AdbRepo", "installApks: commitResult='$commitResult'")

            // 5. 清理
            runSingleCommand("rm -rf $tmpDir", 5000)

            val clean = commitResult.trim()
            when {
                clean.contains("Success") -> "Split APK 安装成功 ($count 个文件, ${totalSize / 1024 / 1024}MB)"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            runSingleCommand("rm -rf $tmpDir", 5000)
            "安装异常: ${e.message}"
        }
    }

    // ── 推送文件（File 版，流式传输，避免 OOM，带重试）──
    suspend fun pushFile(localFile: File, remotePath: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "推送文件: ${localFile.name} -> $remotePath, size=${localFile.length()}")
        android.util.Log.d("AdbRepo", "pushFile(File): ${localFile.name} -> $remotePath, size=${localFile.length()}")
        val maxRetries = 3
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                android.util.Log.d("AdbRepo", "pushFile(File): attempt $attempt, opening SYNC stream")
                val stream = manager.openStream(LocalServices.SYNC)
                android.util.Log.d("AdbRepo", "pushFile(File): SYNC stream opened")
                val os = stream.openOutputStream()
                val inputStream = stream.openInputStream()

                val pathBytes = "$remotePath,0644".toByteArray()
                android.util.Log.d("AdbRepo", "pushFile(File): remotePath=$remotePath, pathBytes.size=${pathBytes.size}")
                // Combine SEND header into one buffer to avoid splitting into multiple WRTE packets
                val sendHeader = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                sendHeader.putInt(0x444e4553) // SEND
                sendHeader.putInt(pathBytes.size)
                sendHeader.put(pathBytes)
                val sendBytes = sendHeader.array()
                android.util.Log.d("AdbRepo", "pushFile(File): SEND header ${sendBytes.size} bytes, hex=${sendBytes.take(16).joinToString("") { "%02x".format(it) }}...")
                os.write(sendBytes)
                android.util.Log.d("AdbRepo", "pushFile(File): SEND header sent OK")

                val chunkSize = 64 * 1024
                val buf = ByteArray(chunkSize)
                var totalSent = 0L
                var chunkCount = 0
                localFile.inputStream().use { fis ->
                    var bytesRead: Int
                    while (fis.read(buf).also { bytesRead = it } != -1) {
                        // Combine DATA header (8 bytes) + payload into one write
                        val packet = ByteBuffer.allocate(8 + bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                        packet.putInt(0x41544144) // DATA
                        packet.putInt(bytesRead)
                        packet.put(buf, 0, bytesRead)
                        os.write(packet.array())
                        totalSent += bytesRead
                        chunkCount++
                        if (chunkCount % 100 == 0) {
                            android.util.Log.d("AdbRepo", "pushFile(File): DATA chunk #$chunkCount sent, totalSent=$totalSent bytes")
                        }
                    }
                }
                android.util.Log.d("AdbRepo", "pushFile(File): all DATA sent, totalSent=$totalSent bytes, chunkCount=$chunkCount")

                // Combine DONE header into one buffer
                val doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                doneHeader.putInt(0x454e4f44) // DONE
                doneHeader.putInt((System.currentTimeMillis() / 1000).toInt())
                os.write(doneHeader.array())
                os.flush()
                android.util.Log.d("AdbRepo", "pushFile(File): DONE sent, flushed")

                val resp = ByteArray(8)
                val readBytes = inputStream.read(resp)
                val respCmd = littleEndianToInt(resp, 0)
                android.util.Log.d("AdbRepo", "pushFile(File): response readBytes=$readBytes, respCmd=0x${respCmd.toString(16)}, hex=${resp.joinToString("") { "%02x".format(it) }}")

                try { stream.close() } catch (_: Exception) {}
                android.util.Log.d("AdbRepo", "pushFile(File): stream closed")

                val result = when (respCmd) {
                    0x59414b4f -> "推送成功: $remotePath"
                    0x4c494146 -> "推送失败"
                    else -> "推送完成"
                }
                WearAdbLogger.i("AdbRepo", "推送文件结果: ${localFile.name} -> $remotePath: $result")
                android.util.Log.d("AdbRepo", "pushFile(File): $result")
                return@withContext result
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.ConnectException) {
                lastException = e
                android.util.Log.w("AdbRepo", "pushFile(File): attempt $attempt/$maxRetries ConnectException: ${e.message}")
                if (attempt < maxRetries) {
                    val backoff = exponentialBackoff(attempt)
                    android.util.Log.d("AdbRepo", "pushFile(File) retry waiting ${backoff}ms")
                    kotlinx.coroutines.delay(backoff)
                }
            } catch (e: Exception) {
                WearAdbLogger.e("AdbRepo", "推送文件异常: ${localFile.name} -> $remotePath: ${e.message}", e)
                android.util.Log.e("AdbRepo", "pushFile(File): exception: ${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext "推送异常: ${e.message}"
            }
        }
        WearAdbLogger.e("AdbRepo", "推送文件全部重试失败: ${localFile.name} -> $remotePath")
        android.util.Log.e("AdbRepo", "pushFile(File): all $maxRetries attempts failed", lastException)
        "推送异常: ${lastException?.message}"
    }

    suspend fun listFiles(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "列目录: path=$path")
        val output = runSingleCommand("ls -Lla $path 2>&1")
        android.util.Log.d("AdbRepo", "listFiles($path) output length=${output.length}, first300=${output.take(300)}")
        val result = AdbOutputParser.parseFileListing(output, path)
        android.util.Log.d("AdbRepo", "listFiles($path) parsed ${result.size} entries")
        result
    }

    suspend fun deleteFile(path: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "删除文件: path=$path")
        val result = runSingleCommand("rm -rf $path")
        WearAdbLogger.i("AdbRepo", "删除文件结果: $path - $result")
        result
    }

    suspend fun createDirectory(path: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "创建目录: path=$path")
        val result = runSingleCommand("mkdir -p $path")
        WearAdbLogger.i("AdbRepo", "创建目录结果: $path - $result")
        result
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "读取文件: path=$path")
        runSingleCommand("cat $path")
    }

    // ── 文件传输 ──
    suspend fun pushFile(localData: ByteArray, remotePath: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "推送文件(ByteArray): ${localData.size} bytes -> $remotePath")
        android.util.Log.d("AdbRepo", "pushFile(ByteArray): ${localData.size} bytes -> $remotePath")
        try {
            val stream = manager.openStream(LocalServices.SYNC)
            android.util.Log.d("AdbRepo", "pushFile(ByteArray): SYNC stream opened")
            val os = stream.openOutputStream()
            val inputStream = stream.openInputStream()

            // SEND header — single buffer
            val pathBytes = "$remotePath,0644".toByteArray()
            val sendBuf = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            sendBuf.putInt(0x444e4553)
            sendBuf.putInt(pathBytes.size)
            sendBuf.put(pathBytes)
            os.write(sendBuf.array())
            android.util.Log.d("AdbRepo", "pushFile(ByteArray): SEND header sent, ${sendBuf.array().size} bytes")

            // DATA chunks — each header+payload as one buffer
            val chunkSize = 64 * 1024
            var offset = 0
            var chunkCount = 0
            while (offset < localData.size) {
                val len = minOf(chunkSize, localData.size - offset)
                val packet = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN)
                packet.putInt(0x41544144) // DATA
                packet.putInt(len)
                packet.put(localData, offset, len)
                os.write(packet.array())
                offset += len
                chunkCount++
                if (chunkCount % 100 == 0) {
                    android.util.Log.d("AdbRepo", "pushFile(ByteArray): DATA chunk #$chunkCount, sent $offset/${localData.size} bytes")
                }
            }
            android.util.Log.d("AdbRepo", "pushFile(ByteArray): all DATA sent, $chunkCount chunks, total $offset bytes")

            // DONE — single buffer
            val doneBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            doneBuf.putInt(0x454e4f44)
            doneBuf.putInt((System.currentTimeMillis() / 1000).toInt())
            os.write(doneBuf.array())
            os.flush()
            android.util.Log.d("AdbRepo", "pushFile(ByteArray): DONE sent, flushed")

            // 读取响应
            val resp = ByteArray(8)
            val readBytes = inputStream.read(resp)
            val respCmd = littleEndianToInt(resp, 0)
            android.util.Log.d("AdbRepo", "pushFile(ByteArray): response readBytes=$readBytes, respCmd=0x${respCmd.toString(16)}, hex=${resp.joinToString("") { "%02x".format(it) }}")

            try { stream.close() } catch (_: Exception) {}
            android.util.Log.d("AdbRepo", "pushFile(ByteArray): stream closed")

            when (respCmd) {
                0x59414b4f -> "推送成功: $remotePath"  // OKAY
                0x4c494146 -> "推送失败"               // FAIL
                else -> "推送完成"
            }
        } catch (e: Exception) {
            "推送异常: ${e.message}"
        }
    }

    suspend fun pullFile(remotePath: String): PullResult = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "拉取文件: remotePath=$remotePath")
        android.util.Log.d("AdbRepo", "pullFile: $remotePath")
        try {
            val stream = manager.openStream(LocalServices.SYNC)
            android.util.Log.d("AdbRepo", "pullFile: SYNC stream opened")
            val os = stream.openOutputStream()
            val inputStream = stream.openInputStream()

            // RECV header — single buffer
            val pathBytes = remotePath.toByteArray()
            val recvBuf = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            recvBuf.putInt(0x56434552) // RECV
            recvBuf.putInt(pathBytes.size)
            recvBuf.put(pathBytes)
            os.write(recvBuf.array())
            os.flush()
            android.util.Log.d("AdbRepo", "pullFile: RECV header sent, ${recvBuf.array().size} bytes")

            // 读取 DATA chunks
            val buffer = java.io.ByteArrayOutputStream()
            val headerBuf = ByteArray(8)
            var failed = false
            var failMsg = ""
            var totalReceived = 0L

            while (true) {
                val read = inputStream.read(headerBuf)
                if (read < 8) {
                    android.util.Log.d("AdbRepo", "pullFile: header read=$read (< 8), stopping")
                    break
                }

                val cmd = littleEndianToInt(headerBuf, 0)
                val size = littleEndianToInt(headerBuf, 4)
                android.util.Log.d("AdbRepo", "pullFile: cmd=0x${cmd.toString(16)}, size=$size")

                when (cmd) {
                    0x41544144 -> { // DATA
                        val data = ByteArray(size)
                        var totalRead = 0
                        while (totalRead < size) {
                            val n = inputStream.read(data, totalRead, size - totalRead)
                            if (n < 0) {
                                android.util.Log.w("AdbRepo", "pullFile: DATA read returned $n at totalRead=$totalRead")
                                break
                            }
                            totalRead += n
                        }
                        buffer.write(data, 0, totalRead)
                        totalReceived += totalRead
                    }
                    0x454e4f44 -> {
                        android.util.Log.d("AdbRepo", "pullFile: DONE received, totalReceived=$totalReceived")
                        break
                    }
                    0x4c494146 -> {       // FAIL
                        android.util.Log.w("AdbRepo", "pullFile: FAIL received")
                        failed = true
                        failMsg = "拉取失败"
                        break
                    }
                    else -> {
                        android.util.Log.w("AdbRepo", "pullFile: unknown cmd=0x${cmd.toString(16)}, breaking")
                        break
                    }
                }
            }

            try { stream.close() } catch (_: Exception) {}

            if (failed) {
                WearAdbLogger.w("AdbRepo", "拉取文件失败: $remotePath - $failMsg")
                PullResult(false, null, failMsg)
            } else {
                WearAdbLogger.i("AdbRepo", "拉取文件成功: $remotePath, size=${buffer.size()}")
                PullResult(true, buffer.toByteArray(), "拉取成功: $remotePath")
            }
        } catch (e: Exception) {
            WearAdbLogger.e("AdbRepo", "拉取文件异常: $remotePath - ${e.message}", e)
            PullResult(false, null, "拉取异常: ${e.message}")
        }
    }

    // ── 屏幕查看（B1: scrcpy-server，无线通道，与 USB 共用 ScreenMirrorEngine） ──

    val mirrorEngine: com.wearadb.adb.ScreenMirrorEngine by lazy {
        com.wearadb.adb.ScreenMirrorEngine(appContext)
    }
    val mirrorStatus get() = mirrorEngine.status
    val mirrorVideoSize get() = mirrorEngine.videoSize
    val mirrorRealSize get() = mirrorEngine.realSize

    fun mirrorTransport(): com.wearadb.adb.MirrorTransport = WirelessMirrorTransport()

    // ── 投屏快速注入（首选 control 通道，失败回退 input 命令） ──
    // 无线侧比 USB 侧更依赖 control 通道：runSingleCommand 的 `input tap` 没有输出，
    // 每次都会先试 exec: 再回退 shell 读标记，单次点击要两轮往返。
    //
    // 红线：control 通道的写是**同步阻塞**调用——libadb 3.1.1 的 `AdbStream.write()`
    // 是 `while (!mWriteReady.compareAndSet(true, false)) wait();`，**没有超时**，只有
    // 收到设备 OKAY 才返回；adbd 在本地 socket 背压时会扣住 OKAY。因此整段注入必须
    // 跑在 IO 线程：历史版本由 ViewModel 的 deviceOp 在主线程直接调用，被拖成
    // `AnrType=input.app`。这里统一切 IO，任何调用方都安全。

    /**
     * 触摸注入。control 通道优先；回退路径只在 ACTION_DOWN 时执行整段手势。
     *
     * 【坐标/w/h 空间红线】scrcpy 服务端 `Device.getPhysicalPoint` 会**硬校验**
     * 消息里的 screenWidth/screenHeight 是否等于当前**视频分辨率**（视频头尺寸），
     * 不相等则整条消息**静默丢弃**（返回 null）——表现为画面正常、触摸全无反应。
     * 因此线协议消息必须用视频分辨率 w/h + 视频空间坐标；调用方传入的是
     * 真实分辨率坐标（`input tap` 回退命令需要真实坐标），这里先换算再编码。
     */
    suspend fun touchInject(
        action: Int,
        x: Int,
        y: Int,
        realW: Int,
        realH: Int,
        pointerId: Long,
        fallbackGesture: suspend () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val video = mirrorEngine.videoSize.value
        if (video == null || video.first <= 0 || video.second <= 0 || realW <= 0 || realH <= 0) {
            // 视频头还没到（画面未出）：control 不可用，走 input 回退
            if (action == com.wearadb.adb.ScrcpyControlProtocol.ACTION_DOWN) fallbackGesture()
            return@withContext
        }
        val vx = (x.toLong() * video.first / realW).toInt().coerceIn(0, video.first - 1)
        val vy = (y.toLong() * video.second / realH).toInt().coerceIn(0, video.second - 1)
        val msg = com.wearadb.adb.ScrcpyControlProtocol.injectTouch(action, vx, vy, video.first, video.second, pointerId)
        if (mirrorEngine.sendControl(msg)) return@withContext
        if (action == com.wearadb.adb.ScrcpyControlProtocol.ACTION_DOWN) fallbackGesture()
    }

    /** 按键注入（control 通道优先，回退 `input keyevent`）。 */
    suspend fun keyInject(keycode: Int) = withContext(Dispatchers.IO) {
        if (mirrorEngine.injectKey(keycode)) return@withContext
        WearAdbLogger.w("AdbRepo", "投屏按键注入回退 input keyevent: keycode=$keycode")
        keyEvent(keycode)
    }

    /** 文本注入（control 通道优先，回退 `input text`）。 */
    suspend fun textInject(text: String) = withContext(Dispatchers.IO) {
        if (mirrorEngine.sendControl(com.wearadb.adb.ScrcpyControlProtocol.injectText(text))) return@withContext
        WearAdbLogger.w("AdbRepo", "投屏文本注入回退 input text")
        inputText(text)
    }

    /** 旋转设备（只有 control 通道能表达；回退为 settings 命令）。 */
    suspend fun rotateInject() = withContext(Dispatchers.IO) {
        if (mirrorEngine.sendControl(com.wearadb.adb.ScrcpyControlProtocol.rotateDevice())) return@withContext
        WearAdbLogger.w("AdbRepo", "投屏旋转注入回退 settings user_rotation")
        runSingleCommand("settings put system user_rotation 1", 8000)
    }

    /** 运行时熄屏 / 亮屏（SET_DISPLAY_POWER 控制消息，无需重启会话，与官方 scrcpy MOD+o 一致）。
     *  off=true 熄灭屏幕（保留投屏）；off=false 点亮。未投屏时 sendControl 返回 false，仅保存状态待下次启动生效。 */
    suspend fun screenPowerOff(off: Boolean) = withContext(Dispatchers.IO) {
        if (mirrorEngine.sendControl(com.wearadb.adb.ScrcpyControlProtocol.setDisplayPower(on = !off))) return@withContext
        WearAdbLogger.w("AdbRepo", "投屏运行时熄屏控制消息发送失败（继续投屏）")
    }

    /** 运行时保持唤醒（直接写 Android 全局设置 stay_on_while_plugged_in，无需重启；退出时由 scrcpy 还原）。
     *  7 = AC|USB|WIRELESS 均保持；0 = 关闭。未充电（仅无线）时按 Android 行为无效果。 */
    suspend fun setStayAwake(on: Boolean) = withContext(Dispatchers.IO) {
        val value = if (on) 7 else 0
        runSingleCommand("settings put global stay_on_while_plugged_in $value", 8000)
    }

    private inner class WirelessMirrorTransport : com.wearadb.adb.MirrorTransport {

        /**
         * 镜像启动期的命令/推送串行化闸门。
         *
         * 起因：ScreenMirrorEngine 为了省掉一次串行往返，把「推送 server jar」与
         * 「取设备分辨率」并行化了。USB 通道侧是自研实现（`UsbAdbConnection.nextLocalId`
         * 用 AtomicInteger、流注册表为 ConcurrentHashMap），可安全并发；但无线侧的
         * libadb-android 3.1.1 **不是线程安全的**（已核对源码）：
         *   - `AdbConnection.open()` 的 `int localId = ++mLastLocalId`（mLastLocalId 是
         *     普通 int，非原子）——并发调用会拿到**重复的 localId**，两条流互相顶掉；
         *   - `AdbStream.read()` 的非阻塞快路径 `mReadBuffer.hasRemaining()/put()/flip()`
         *     完全无锁，而 `mIsClosed` 是不加 volatile 的 boolean，跨线程发布不可见，
         *     读线程可能永远不因对端 CLSE 而退出（表现为"当前投屏卡死到超时"）。
         *
         * 因此镜像启动阶段的批量命令一律走此闸门串行执行（代价：省下的那次往返又还
         * 回去了，但换来正确性——并行化的收益主要在 USB 通道）。**投屏进行中的注入
         * 路径不加锁**：control 流是单条流的单向写，且要让拖动跟手必须无锁。
         */
        private val startupGate = Mutex()

        override suspend fun executeCommand(cmd: String): String = runSingleCommand(cmd, 10000)

        /** 启动期命令（可被并行调用方触发）：走闸门串行。 */
        override suspend fun executeCommandSerialized(cmd: String): String =
            startupGate.withLock { runSingleCommand(cmd, 10000) }

        override suspend fun pushFileTo(localFile: File, remotePath: String): Boolean =
            pushFile(localFile, remotePath).contains("成功")

        /** 启动期推送：与同批命令共用闸门，避免与 `wm size` 抢 mLastLocalId。 */
        override suspend fun pushFileToSerialized(localFile: File, remotePath: String): Boolean =
            startupGate.withLock { pushFile(localFile, remotePath).contains("成功") }

        override suspend fun openShellStream(cmd: String): com.wearadb.adb.MirrorStream =
            WirelessMirrorStream(manager.openStream("shell:$cmd"))

        /**
         * 连接抽象套接字。重试循环里失败连接会被立刻 close()，此时 libadb-android 的
         * 连接线程可能正把 CLSE 分发给该流；这对库本身是常规路径，不做额外串行化
         * （套接字连接本就要靠"失败即重试"发现 server 就绪）。
         */
        override suspend fun openAbstractSocket(dest: String, timeoutMs: Long): com.wearadb.adb.MirrorStream? = try {
            val s = manager.openStream(dest)
            if (!s.isClosed) WirelessMirrorStream(s) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * libadb-android AdbStream → MirrorStream 适配。
     *
     * **并发红线**：读取端（投屏读循环）与关闭端（stop()/引擎 finally）来自不同线程，
     * 而 `input` 字段的"检查-再赋值"与底层库的 `read()` 快路径都不是线程安全的
     * （见 WirelessMirrorTransport 注释）。这里用 `@Volatile`（保证跨线程可见性）
     * + 局部变量（消除检查-再赋值竞态）把两端钉死，避免出现"两个 InputStream 竞争
     * 同一个 mReadBuffer"或"关闭后仍持有旧流"。
     */
    private class WirelessMirrorStream(private val s: AdbStream) : com.wearadb.adb.MirrorStream {

        @Volatile
        private var input: java.io.InputStream? = null

        @Volatile
        private var output: java.io.OutputStream? = null

        override fun readBlocking(timeoutMs: Long): ByteArray? = try {
            // 先读 volatile 到局部变量，避免 close() 清空字段后 read 仍用旧引用
            val ins = input ?: s.openInputStream().also { input = it }
            val buf = ByteArray(64 * 1024)
            val n = ins.read(buf) // 阻塞直到 ≥1 字节 / EOF(-1)；流关闭时抛异常 → null
            if (n <= 0) null else buf.copyOf(n)
        } catch (_: Exception) {
            null
        }

        override fun writeBytes(data: ByteArray): Boolean = try {
            val os = output ?: s.openOutputStream().also { output = it }
            os.write(data)
            os.flush()
            true
        } catch (_: Exception) {
            false
        }

        override val isClosed: Boolean get() = s.isClosed
        override val isOpen: Boolean get() = !s.isClosed

        /**
         * 幂等关闭。先把字段置空再关（配合 readBlocking 的局部变量取用），
         * 使并发读线程在字段被清空后不会再新开一条 InputStream。
         */
        override fun close() {
            val i = input
            val o = output
            input = null
            output = null
            try { i?.close() } catch (_: Exception) {}
            try { o?.close() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
    }

    // ── 高级操作 ──
    /**
     * 通用命令执行 —— 公开入口，与 `UsbAdbRepository.executeCommand(command, timeoutMs)` 一一对应。
     * 无线侧此前只有私有的 runSingleCommand，UI 层要跑任意命令只能自己开 shell 流读 8 秒；
     * 两侧形状对齐后，ViewModel 的 Shell 分支可以写成同一句路由。
     */
    suspend fun executeCommand(command: String, timeoutMs: Long = 15000): String =
        runSingleCommand(command, timeoutMs)

    suspend fun reboot() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "重启设备"); runSingleCommand("reboot") }
    suspend fun rebootRecovery() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "重启到Recovery"); runSingleCommand("reboot recovery") }
    suspend fun rebootBootloader() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "重启到Bootloader"); runSingleCommand("reboot bootloader") }
    suspend fun shutdown() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "关机"); runSingleCommand("reboot -p") }
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "截屏"); AdvancedOps.screenshot(manager) }
    suspend fun tap(x: Int, y: Int) = withContext(Dispatchers.IO) { runSingleCommand("input tap $x $y") }
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300) = withContext(Dispatchers.IO) {
        runSingleCommand("input swipe $x1 $y1 $x2 $y2 $dur")
    }
    suspend fun keyEvent(code: Int) = withContext(Dispatchers.IO) { runSingleCommand("input keyevent $code") }
    suspend fun enableWifi() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "开启WiFi"); runSingleCommand("svc wifi enable") }
    suspend fun disableWifi() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "关闭WiFi"); runSingleCommand("svc wifi disable") }
    suspend fun enableBluetooth() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "开启蓝牙"); runSingleCommand("svc bluetooth enable") }
    suspend fun disableBluetooth() = withContext(Dispatchers.IO) { WearAdbLogger.i("AdbRepo", "关闭蓝牙"); runSingleCommand("svc bluetooth disable") }
    suspend fun volumeUp() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 24") }
    suspend fun volumeDown() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 25") }
    suspend fun volumeMute() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 164") }
    suspend fun screenOn() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 26") }
    suspend fun screenOff() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 26") }
    suspend fun inputText(text: String) = withContext(Dispatchers.IO) { runSingleCommand("input text \"$text\"") }

    // ── 交互式 Shell ──
    suspend fun openShell(command: String = ""): AdbStream = withContext(Dispatchers.IO) {
        WearAdbLogger.i("AdbRepo", "打开Shell: command=${command.take(100)}")
        if (command.isEmpty()) manager.openStream(LocalServices.SHELL)
        else manager.openStream(LocalServices.SHELL, command)
    }

    suspend fun removeDevice(address: String) = deviceRepository.removeDevice(address)
    suspend fun toggleFavorite(address: String) = deviceRepository.toggleFavorite(address)

    fun destroy() { stopDiscovery() }

    // ── 工具方法 ──
    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun littleEndianToInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
