package com.wearadb.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wearadb.adb.AdbOutputParser
import com.wearadb.adb.AdvancedOps
import com.wearadb.adb.WearAdbConnectionManager
import com.wearadb.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

// ── ADB 仓库 ──
@Singleton
class AdbRepository @Inject constructor(
    private val deviceRepository: DeviceRepository,
    @ApplicationContext private val appContext: Context
) {
    private val manager: WearAdbConnectionManager by lazy {
        WearAdbConnectionManager.getInstance(appContext)
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
    }

    fun startDiscovery() {
        if (_isDiscovering.value) return
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
    suspend fun pair(host: String, port: Int, code: String): PairingResult = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("AdbRepo", "pair() called: host=$host, port=$port, code=${code.length}chars")
            _connectionState.value = ConnectionState.AUTHENTICATING
            manager.setHostAddress(host)
            android.util.Log.d("AdbRepo", "Calling manager.pair($port, code)...")
            val success = manager.pair(port, code)
            android.util.Log.d("AdbRepo", "pair() result: success=$success")
            _connectionState.value = ConnectionState.DISCONNECTED
            if (success) {
                PairingResult(true, host, port, "配对成功")
            } else {
                PairingResult(false, host, port, "配对失败")
            }
        } catch (e: Exception) {
            android.util.Log.e("AdbRepo", "pair() exception: ${e.javaClass.simpleName}: ${e.message}", e)
            _connectionState.value = ConnectionState.ERROR
            PairingResult(false, host, port, "配对异常: ${e.message}")
        }
    }

    // ── 连接 ──
    suspend fun connect(host: String, port: Int = 5555, useTls: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("AdbRepo", "connect() called: host=$host, port=$port, useTls=$useTls")
            _connectionState.value = ConnectionState.CONNECTING

            var success = false

            // 尝试 TLS 连接
            if (useTls) {
                try {
                    android.util.Log.d("AdbRepo", "Connecting via TLS...")
                    manager.setHostAddress(host)
                    success = manager.connectTls(appContext, 5000)
                } catch (e: Exception) {
                    android.util.Log.w("AdbRepo", "TLS failed, falling back to TCP: ${e.message}")
                    success = false
                }
            }

            // TLS 未成功则降级为普通 TCP
            if (!success) {
                android.util.Log.d("AdbRepo", "Connecting via TCP to $host:$port ...")
                success = manager.connect(host, port)
            }

            android.util.Log.d("AdbRepo", "connect() result: success=$success")
            if (success) {
                _connectionState.value = ConnectionState.CONNECTED
                _deviceBanner.value = "Connected to $host:$port"

                deviceRepository.saveDevice(
                    SavedDevice(
                        host = host, port = port,
                        name = _deviceBanner.value,
                        lastConnected = System.currentTimeMillis()
                    )
                )
                deviceRepository.saveLastHost(host)
                deviceRepository.saveLastPort(port)
            } else {
                android.util.Log.w("AdbRepo", "connect() returned false")
                _connectionState.value = ConnectionState.ERROR
            }
        } catch (e: Exception) {
            android.util.Log.e("AdbRepo", "connect() exception: ${e.javaClass.simpleName}: ${e.message}", e)
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    suspend fun autoConnect() = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            val success = manager.autoConnect(appContext, 5000)
            if (success) {
                _connectionState.value = ConnectionState.CONNECTED
                _deviceBanner.value = "Auto-connected"
            } else {
                _connectionState.value = ConnectionState.ERROR
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnect() {
        try { manager.disconnect() } catch (_: Exception) {}
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceBanner.value = ""
    }

    fun isConnected(): Boolean = manager.isConnected

    suspend fun getLastHost(): String = deviceRepository.getLastHost()
    suspend fun getLastPort(): Int = deviceRepository.getLastPort()

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
                } catch (e: java.net.ConnectException) {
                    lastException = e
                    android.util.Log.w("AdbRepo", "runSingleCommand: attempt $attempt/$maxRetries failed: ${e.message}")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(500L * attempt) // 递增等待
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
        val stream = openStream()
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
        result.copy(storageTotal = storageTotal, storageUsed = storageUsed, storageFree = storageFree)
    }

    /**
     * 从 ADB shell 输出中提取标记之间的内容。
     * 去掉所有 prompt 行和命令回显行。
     */
    private fun cleanShellOutput(raw: String): String {
        val markers = listOf("==PROPS==", "==BATTERY==", "==DISPLAY==", "==MEM==", "==UPTIME==", "==STORAGE==")
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
        val combined = runSingleCommand(
            "echo ==FULL==; pm list packages -f; echo ==SYSTEM==; pm list packages -s; echo ==THIRD==; pm list packages -3"
        )
        val sections = combined.split(Regex("==FULL==|==SYSTEM==|==THIRD=="))
        val fullOutput = sections.getOrElse(1) { "" }.trim()
        val systemOutput = sections.getOrElse(2) { "" }.trim()
        val thirdPartyOutput = sections.getOrElse(3) { "" }.trim()
        val systemPkgs = systemOutput.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
        val thirdPartyPkgs = thirdPartyOutput.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
        android.util.Log.d("AdbRepo", "getInstalledPackages() full=${fullOutput.length}, system=${systemPkgs.size}, thirdParty=${thirdPartyPkgs.size}")
        val result = AdbOutputParser.parsePackageListWithFilter(fullOutput, systemPkgs, thirdPartyPkgs)
        android.util.Log.d("AdbRepo", "getInstalledPackages() parsed ${result.size} apps, system=${result.count { it.isSystem }}")
        result
    }

    suspend fun uninstallApp(pkg: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("pm uninstall $pkg")
    }

    suspend fun clearAppData(pkg: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("pm clear $pkg")
    }

    suspend fun forceStopApp(pkg: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("am force-stop $pkg")
    }

    suspend fun disableApp(pkg: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("pm disable-user $pkg")
    }

    suspend fun enableApp(pkg: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("pm enable $pkg")
    }

    // ── 安装 APK ──
    suspend fun installApk(apkData: ByteArray): String = withContext(Dispatchers.IO) {
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
                clean.contains("Success") -> "安装成功"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
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
                clean.contains("Success") -> "安装成功"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
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
                android.util.Log.d("AdbRepo", "pushFile(File): $result")
                return@withContext result
            } catch (e: java.net.ConnectException) {
                lastException = e
                android.util.Log.w("AdbRepo", "pushFile(File): attempt $attempt/$maxRetries ConnectException: ${e.message}")
                if (attempt < maxRetries) kotlinx.coroutines.delay(500L * attempt)
            } catch (e: Exception) {
                android.util.Log.e("AdbRepo", "pushFile(File): exception: ${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext "推送异常: ${e.message}"
            }
        }
        android.util.Log.e("AdbRepo", "pushFile(File): all $maxRetries attempts failed", lastException)
        "推送异常: ${lastException?.message}"
    }

    suspend fun listFiles(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val output = runSingleCommand("ls -Lla $path 2>&1")
        android.util.Log.d("AdbRepo", "listFiles($path) output length=${output.length}, first300=${output.take(300)}")
        val result = AdbOutputParser.parseFileListing(output, path)
        android.util.Log.d("AdbRepo", "listFiles($path) parsed ${result.size} entries")
        result
    }

    suspend fun deleteFile(path: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("rm -rf $path")
    }

    suspend fun createDirectory(path: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("mkdir -p $path")
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        runSingleCommand("cat $path")
    }

    // ── 文件传输 ──
    suspend fun pushFile(localData: ByteArray, remotePath: String): String = withContext(Dispatchers.IO) {
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

            if (failed) PullResult(false, null, failMsg)
            else PullResult(true, buffer.toByteArray(), "拉取成功: $remotePath")
        } catch (e: Exception) {
            PullResult(false, null, "拉取异常: ${e.message}")
        }
    }

    // ── 高级操作 ──
    suspend fun reboot() = withContext(Dispatchers.IO) { runSingleCommand("reboot") }
    suspend fun rebootRecovery() = withContext(Dispatchers.IO) { runSingleCommand("reboot recovery") }
    suspend fun rebootBootloader() = withContext(Dispatchers.IO) { runSingleCommand("reboot bootloader") }
    suspend fun shutdown() = withContext(Dispatchers.IO) { runSingleCommand("reboot -p") }
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) { AdvancedOps.screenshot(manager) }
    suspend fun tap(x: Int, y: Int) = withContext(Dispatchers.IO) { runSingleCommand("input tap $x $y") }
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300) = withContext(Dispatchers.IO) {
        runSingleCommand("input swipe $x1 $y1 $x2 $y2 $dur")
    }
    suspend fun keyEvent(code: Int) = withContext(Dispatchers.IO) { runSingleCommand("input keyevent $code") }
    suspend fun enableWifi() = withContext(Dispatchers.IO) { runSingleCommand("svc wifi enable") }
    suspend fun disableWifi() = withContext(Dispatchers.IO) { runSingleCommand("svc wifi disable") }
    suspend fun enableBluetooth() = withContext(Dispatchers.IO) { runSingleCommand("svc bluetooth enable") }
    suspend fun disableBluetooth() = withContext(Dispatchers.IO) { runSingleCommand("svc bluetooth disable") }
    suspend fun volumeUp() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 24") }
    suspend fun volumeDown() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 25") }
    suspend fun volumeMute() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 164") }
    suspend fun screenOn() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 26") }
    suspend fun screenOff() = withContext(Dispatchers.IO) { runSingleCommand("input keyevent 26") }
    suspend fun inputText(text: String) = withContext(Dispatchers.IO) { runSingleCommand("input text \"$text\"") }

    // ── 交互式 Shell ──
    suspend fun openShell(command: String = ""): AdbStream = withContext(Dispatchers.IO) {
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
