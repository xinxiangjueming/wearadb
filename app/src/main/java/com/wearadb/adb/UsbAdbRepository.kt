package com.wearadb.adb

import android.content.Context
import android.util.Log
import android.view.Surface
import com.wearadb.log.WearAdbLogger
import com.wearadb.data.repository.PullResult
import com.wearadb.data.repository.parseApkPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for wired USB ADB operations.
 * Integrates UsbAdbManager with the rest of the app.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class UsbAdbRepository @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "UsbAdbRepository"

        // ── B1 有线投屏（scrcpy-server v2.7）常量 ──
        /** jar 内 BuildConfig.VERSION_NAME（已用 dex 常量池校验），server 启动首参必须匹配，否则直接抛异常 */
        private const val SCRCPY_VERSION = "2.7"
        private const val SCRCPY_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SCRCPY_ASSET_PATH = "scrcpy/scrcpy-server.jar"
        /** scrcpy 2.x 线协议 codec id："h264" 的 ASCII（大端） */
        private const val CODEC_ID_H264 = 0x68323634
        /** 12 字节包头: [pts_flags:8 BE][len:4 BE] */
        private const val SC_PACKET_HEADER_SIZE = 12
        private const val SC_PACKET_FLAG_CONFIG = 1L shl 63
        private const val SC_PACKET_PTS_MASK = (1L shl 62) - 1
    }

    // ── State ──
    private val _connectionState = MutableStateFlow(UsbAdbConnectionState.DISCONNECTED)
    val connectionState: StateFlow<UsbAdbConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<UsbAdbDeviceInfo?>(null)
    val connectedDevice: StateFlow<UsbAdbDeviceInfo?> = _connectedDevice.asStateFlow()

    private val _connectLog = MutableStateFlow("")
    val connectLog: StateFlow<String> = _connectLog.asStateFlow()
    private val logLines = mutableListOf<String>()

    // ── Deferred Manager (RSA key gen off main thread) ──
    private data class AdbComponents(
        val manager: UsbAdbManager,
        val privateKey: PrivateKey,
        val certificate: Certificate
    )
    private val initDeferred = CompletableDeferred<AdbComponents>()

    init {
        // Move RSA key generation + file IO off main thread
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val (pk, cert) = loadOrGenerateKeyPair()
                val mgr = UsbAdbManager(appContext, pk, cert, onConnectionLost = { handleLinkLost() }) { msg ->
                    synchronized(logLines) {
                        logLines.add(msg)
                        _connectLog.value = logLines.joinToString("\n")
                    }
                }
                initDeferred.complete(AdbComponents(mgr, pk, cert))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Async init failed", e)
                initDeferred.completeExceptionally(e)
            }
        }
    }

    /** Await manager initialization (runs on IO, never blocks main thread). */
    private suspend fun awaitManager(): UsbAdbManager {
        return withTimeout(30_000) { initDeferred.await().manager }
    }

    // ── Scanning ──

    suspend fun scanDevices(): List<UsbAdbDeviceInfo> = withContext(Dispatchers.IO) {
        awaitManager().scanDevices()
    }

    // ── Connection ──

    suspend fun connect(deviceInfo: UsbAdbDeviceInfo): Boolean = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB连接: ${deviceInfo.displayName}")
        synchronized(logLines) { logLines.clear(); _connectLog.value = "" }
        _connectionState.value = UsbAdbConnectionState.CONNECTING

        val mgr = awaitManager()
        val success = mgr.connect(deviceInfo)
        if (success) {
            WearAdbLogger.i("UsbAdb", "USB连接成功: ${deviceInfo.displayName}")
            _connectionState.value = UsbAdbConnectionState.CONNECTED
            _connectedDevice.value = deviceInfo
        } else {
            WearAdbLogger.w("UsbAdb", "USB连接失败: ${deviceInfo.displayName}")
            _connectionState.value = UsbAdbConnectionState.ERROR
            _connectedDevice.value = null
        }
        success
    }

    suspend fun disconnect() {
        WearAdbLogger.i("UsbAdb", "USB断开连接")
        mirrorEngine.stop()
        try {
            awaitManager().disconnect()
        } catch (_: Exception) {}
        _connectionState.value = UsbAdbConnectionState.DISCONNECTED
        _connectedDevice.value = null
    }

    /**
     * 链路死亡上报（UsbAdbConnection 读取线程 EOF / USB 设备拔出广播，经
     * UsbAdbManager.handleConnectionLost 汇聚）。
     *
     * 只更新连接状态 + 记日志，**不在此处 mirrorEngine.stop()**：链路死亡时
     * 引擎的流已被 close，读循环会读到关闭哨兵自行收场并显示 Error
     * （比静默归 Idle 更可观察，见 ScreenMirrorEngine.finally 的 abnormalExit 分支）。
     * 必须非阻塞地切到协程：回调可能在读取线程上触发。
     */
    private fun handleLinkLost() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (_connectionState.value == UsbAdbConnectionState.DISCONNECTED) return@launch
            WearAdbLogger.w("UsbAdb", "USB连接已断开（链路失效或设备拔出）")
            _connectionState.value = UsbAdbConnectionState.DISCONNECTED
            _connectedDevice.value = null
        }
    }

    val isConnected: Boolean
        get() = try {
            initDeferred.getCompleted().manager.isConnected
        } catch (_: Exception) { false }

    // ── Commands ──

    /**
     * Execute a shell command over USB ADB.
     * Returns the command output as a string.
     */
    suspend fun executeCommand(command: String, timeoutMs: Long = 15000): String = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext "未连接"
        try {
            val stream = conn.openShell(command)
            val result = stream.readAll(timeoutMs)
            conn.closeStream(stream)
            result
        } catch (e: Exception) {
            "执行失败: ${e.message}"
        }
    }

    /**
     * Get device info via a single combined shell command.
     * Uses the same comprehensive command as the wireless ADB path,
     * and parses with AdbOutputParser for full DeviceInfo fields.
     */
    suspend fun getDeviceInfo(): com.wearadb.data.model.DeviceInfo = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext com.wearadb.data.model.DeviceInfo()

        try {
            android.util.Log.d(TAG, "getDeviceInfo: 开始执行命令...")
            val stream = conn.openShell(
                "echo ==PROPS==; getprop; " +
                "echo ==BATTERY==; dumpsys battery; " +
                "cat /sys/class/power_supply/battery/uevent 2>/dev/null; " +
                "cat /sys/class/power_supply/Battery/uevent 2>/dev/null; " +
                "dumpsys batterystats 2>/dev/null | grep -i 'charge_full\\|charge_full_design\\|capacity'; " +
                "echo ==DISPLAY==; wm size; wm density; " +
                "echo ==MEM==; cat /proc/meminfo | head -3; " +
                "echo ==UPTIME==; uptime; " +
                "echo ==STORAGE==; df -h /data 2>/dev/null || df -h /storage/emulated 2>/dev/null || df -h 2>/dev/null | head -5"
            )
            android.util.Log.d(TAG, "getDeviceInfo: stream opened, reading...")
            val raw = stream.readAll(30000)
            conn.closeStream(stream)
            android.util.Log.d(TAG, "getDeviceInfo: raw length=${raw.length}, content=${raw.take(300)}")

            val result = AdbOutputParser.parseDeviceInfo(raw)
            val (storageTotal, storageUsed, storageFree) = AdbOutputParser.parseStorageInfo(raw)
            var info = result.copy(storageTotal = storageTotal, storageUsed = storageUsed, storageFree = storageFree)

            // root 专属：循环次数 + UFS 闪存寿命 + 电池容量（仅已 root 时 su 可用；失败静默跳过）
            //   设计容量 / 当前满容量对 shell 身份 Permission denied，仅 root 可读；健康度% 依赖二者，故在此合并时计算。
            try {
                val rootStream = conn.openShell(
                    "echo ==EXTRA==; su -c 'echo CYCLE; cat /sys/class/power_supply/battery/cycle_count 2>/dev/null; " +
                    "D=\$(find /sys/devices -type d -name health_descriptor 2>/dev/null | head -1); " +
                    "echo UFS; echo \$D; cat \$D/life_time_estimation_a 2>/dev/null; echo SEP; cat \$D/life_time_estimation_b 2>/dev/null; " +
                    "echo CFULL; cat /sys/class/power_supply/battery/charge_full 2>/dev/null; " +
                    "echo CDESIGN; cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null'"
                )
                val rootRaw = rootStream.readAll(8000)
                conn.closeStream(rootStream)
                AdbOutputParser.parseRootExtras(rootRaw)?.let { ex ->
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
            } catch (re: Exception) {
                android.util.Log.w(TAG, "getDeviceInfo: root extras failed: ${re.message}")
            }

            info
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getDeviceInfo failed: ${e.message}")
            com.wearadb.data.model.DeviceInfo()
        }
    }

    /**
     * Get installed packages via shell command.
     */
    suspend fun getInstalledPackages(): List<com.wearadb.data.model.AppEntry> = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext emptyList()
        try {
            val combined = conn.openShell(
                "echo ==FULL==; pm list packages -f; echo ==SYSTEM==; pm list packages -s; echo ==THIRD==; pm list packages -3; echo ==DISABLED==; pm list packages -d"
            ).readAll(15000)
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
            AdbOutputParser.parsePackageListWithFilter(fullOutput, systemPkgs, thirdPartyPkgs, disabledPkgs)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getInstalledPackages failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Take a screenshot over wired USB ADB.
     * Runs `screencap -p` and reads the raw PNG bytes from the stream.
     */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext null
        try {
            android.util.Log.d(TAG, "screenshot: opening shell stream...")
            val stream = conn.openShell("screencap -p")
            val rawBytes = stream.readAllBytes(15000)
            conn.closeStream(stream)
            android.util.Log.d(TAG, "screenshot: read ${rawBytes.size} bytes")

            // Find PNG signature (skip any shell echo prefix)
            val pngStart = findPngSignature(rawBytes)
            if (pngStart < 0) {
                android.util.Log.e(TAG, "screenshot: no PNG signature found")
                return@withContext null
            }
            val pngData = if (pngStart > 0) rawBytes.copyOfRange(pngStart, rawBytes.size) else rawBytes

            // Trim to IEND marker (remove trailing shell prompt garbage)
            val trimmed = trimToIend(pngData)
            val result = trimmed ?: pngData
            if (result.size > 64) {
                android.util.Log.d(TAG, "screenshot: success, ${result.size} bytes")
                result
            } else {
                android.util.Log.e(TAG, "screenshot: PNG too small (${result.size} bytes)")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "screenshot failed: ${e.message}")
            null
        }
    }

    private fun findPngSignature(data: ByteArray): Int {
        if (data.size < 8) return -1
        for (i in 0..minOf(data.size - 8, 512)) {
            if (data[i] == 0x89.toByte() && data[i + 1] == 0x50.toByte() &&
                data[i + 2] == 0x4E.toByte() && data[i + 3] == 0x47.toByte()) return i
        }
        return -1
    }

    private fun trimToIend(data: ByteArray): ByteArray? {
        if (data.size < 12) return null
        val searchRange = minOf(data.size - 12, 4096)
        for (i in data.size - 12 downTo data.size - 12 - searchRange) {
            if (i < 0) break
            if (data[i] == 0x49.toByte() && data[i + 1] == 0x45.toByte() &&
                data[i + 2] == 0x4E.toByte() && data[i + 3] == 0x44.toByte()) {
                return data.copyOf(i + 12)
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════
    // ── 屏幕查看（scrcpy-server，USB/无线共用 ScreenMirrorEngine） ──
    // 协议与数据流见 ScreenMirrorEngine.kt
    // ══════════════════════════════════════════════════════════

    val mirrorEngine = ScreenMirrorEngine(appContext)

    val mirrorStatus: StateFlow<MirrorStatus> get() = mirrorEngine.status

    /** 编码分辨率（scrcpy 视频头），触摸映射用 */
    val mirrorVideoSize: StateFlow<Pair<Int, Int>?> get() = mirrorEngine.videoSize

    /** 设备真实分辨率（wm size），input tap 坐标系用 */
    val mirrorRealSize: StateFlow<Pair<Int, Int>?> get() = mirrorEngine.realSize

    fun mirrorTransport(): MirrorTransport = UsbMirrorTransport()

    // ── 投屏快速注入（首选 control 通道，失败回退 input 命令） ──
    //
    // 红线：control 通道的写是**同步阻塞**调用（无线侧 libadb 的 AdbStream.write()
    // 无超时，等设备 OKAY；USB 侧靠内部流控超时兜底），因此整段注入必须跑在 IO 线程。
    // 历史版本由 ViewModel 的 deviceOp 在主线程直接调用，链路抖动时被拖成
    // `AnrType=input.app`。这里统一在仓储层切 IO，任何调用方都安全。

    /**
     * 触摸注入。control 通道可用时走 scrcpy 线协议（单向写、数毫秒、支持实时拖动）；
     * 否则回退到 `input` 命令——慢（300-600ms）但保证功能不消失。
     * 命令注入只能用完整手势表达，故回退路径只能发"点击"或"滑动"。
     *
     * 【坐标/w/h 空间红线】服务端 `Device.getPhysicalPoint` 硬校验消息里的
     * screenWidth/screenHeight 必须等于当前**视频分辨率**，否则整条消息静默丢弃
     * （画面正常、触摸全无反应）。调用方传真实分辨率坐标（`input` 回退需要真实坐标），
     * 这里换算到视频空间后再编码，与无线侧 [AdbRepository.touchInject] 同形。
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
            if (action == ScrcpyControlProtocol.ACTION_DOWN) fallbackGesture()
            return@withContext
        }
        val vx = (x.toLong() * video.first / realW).toInt().coerceIn(0, video.first - 1)
        val vy = (y.toLong() * video.second / realH).toInt().coerceIn(0, video.second - 1)
        val msg = ScrcpyControlProtocol.injectTouch(action, vx, vy, video.first, video.second, pointerId)
        if (mirrorEngine.sendControl(msg)) return@withContext
        when (action) {
            ScrcpyControlProtocol.ACTION_DOWN -> fallbackGesture()
            // MOVE/UP 在回退路径下无事可做：整体手势由 DOWN 时的 fallback 一次完成
        }
    }

    /** 按键注入（control 通道优先，回退 `input keyevent`）。 */
    suspend fun keyInject(keycode: Int) = withContext(Dispatchers.IO) {
        if (mirrorEngine.injectKey(keycode)) return@withContext
        WearAdbLogger.w("UsbAdb", "投屏按键注入回退 input keyevent: keycode=$keycode")
        executeCommand("input keyevent $keycode", 8000)
    }

    /** 文本注入（control 通道优先，回退 `input text`）。 */
    suspend fun textInject(text: String) = withContext(Dispatchers.IO) {
        if (mirrorEngine.sendControl(ScrcpyControlProtocol.injectText(text))) return@withContext
        WearAdbLogger.w("UsbAdb", "投屏文本注入回退 input text")
        executeCommand("input text \"$text\"", 8000)
    }

    /** 旋转设备（只有 control 通道能表达；回退为 settings 命令）。 */
    suspend fun rotateInject() = withContext(Dispatchers.IO) {
        if (mirrorEngine.sendControl(ScrcpyControlProtocol.rotateDevice())) return@withContext
        WearAdbLogger.w("UsbAdb", "投屏旋转注入回退 settings user_rotation")
        executeCommand("settings put system user_rotation 1", 8000)
    }

    /** 运行时熄屏 / 亮屏（SET_DISPLAY_POWER 控制消息，无需重启会话，与官方 scrcpy MOD+o 一致）。
     *  off=true 熄灭屏幕（保留投屏）；off=false 点亮。未投屏时 sendControl 返回 false，仅保存状态待下次启动生效。 */
    suspend fun screenPowerOff(off: Boolean) = withContext(Dispatchers.IO) {
        if (mirrorEngine.sendControl(ScrcpyControlProtocol.setDisplayPower(on = !off))) return@withContext
        WearAdbLogger.w("UsbAdb", "投屏运行时熄屏控制消息发送失败（继续投屏）")
    }

    /** 运行时保持唤醒（直接写 Android 全局设置 stay_on_while_plugged_in，无需重启；退出时由 scrcpy 还原）。
     *  7 = AC|USB|WIRELESS 均保持；0 = 关闭。未充电（仅无线）时按 Android 行为无效果。 */
    suspend fun setStayAwake(on: Boolean) = withContext(Dispatchers.IO) {
        val value = if (on) 7 else 0
        executeCommand("settings put global stay_on_while_plugged_in $value", 8000)
    }

    private inner class UsbMirrorTransport : MirrorTransport {

        /**
         * 启动期命令闸门。
         *
         * 与无线侧 [AdbRepository.WirelessMirrorTransport] 的 startupGate 对称：
         * 引擎在启动阶段会并行发起「推送 server jar」与「取 wm size」。USB 通道
         * 在协议层已可安全并发（localId 用 AtomicInteger、流注册表是 ConcurrentHashMap、
         * 总线写 lock 保证消息原子性），但并行发起两条 shell 流仍会平白增加
         * 一次 OPEN/OKAY 往返的争用；这里按无线侧同样的形状串行化，
         * 保持两个通道行为一致，避免"只在无线侧验证过"的盲区。
         */
        private val startupGate = Mutex()

        override suspend fun executeCommand(cmd: String): String =
            this@UsbAdbRepository.executeCommand(cmd, 10000)

        override suspend fun executeCommandSerialized(cmd: String): String =
            startupGate.withLock { this@UsbAdbRepository.executeCommand(cmd, 10000) }

        override suspend fun pushFileTo(localFile: File, remotePath: String): Boolean =
            pushFile(localFile, remotePath).contains("成功")

        override suspend fun pushFileToSerialized(localFile: File, remotePath: String): Boolean =
            startupGate.withLock { pushFile(localFile, remotePath).contains("成功") }

        override suspend fun openShellStream(cmd: String): MirrorStream {
            val conn = awaitManager().getConnection()
                ?: throw java.io.IOException("USB 未连接")
            return UsbMirrorStream(conn.openShell(cmd), conn)
        }

        override suspend fun openAbstractSocket(dest: String, timeoutMs: Long): MirrorStream? {
            val conn = awaitManager().getConnection() ?: return null
            val s = conn.openStream(dest, timeoutMs)
            return if (s.isOpen) UsbMirrorStream(s, conn) else null
        }
    }

    // ── 文件推送 ──

    suspend fun pushFile(localFile: File, remotePath: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB推送文件: ${localFile.name} -> $remotePath, size=${localFile.length()}")
        android.util.Log.d(TAG, "pushFile: ${localFile.name} (${localFile.length()} bytes) -> $remotePath")
        val conn = awaitManager().getConnection() ?: return@withContext "未连接"
        try {
            val stream = conn.openSync()

            // SEND header — single buffer, write via WRTE
            val pathBytes = "$remotePath,0644".toByteArray()
            val sendBuf = java.nio.ByteBuffer.allocate(8 + pathBytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            sendBuf.putInt(0x444e4553)
            sendBuf.putInt(pathBytes.size)
            sendBuf.put(pathBytes)
            stream.write(sendBuf.array(), conn)
            android.util.Log.d(TAG, "pushFile: SEND sent, ${sendBuf.array().size} bytes")

            // DATA chunks
            val chunkSize = 64 * 1024
            val buf = ByteArray(chunkSize)
            var totalSent = 0L
            var chunkCount = 0
            localFile.inputStream().use { fis ->
                var bytesRead: Int
                while (fis.read(buf).also { bytesRead = it } != -1) {
                    val packet = java.nio.ByteBuffer.allocate(8 + bytesRead).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    packet.putInt(0x41544144)
                    packet.putInt(bytesRead)
                    packet.put(buf, 0, bytesRead)
                    stream.write(packet.array(), conn)
                    totalSent += bytesRead
                    chunkCount++
                    if (chunkCount % 100 == 0) {
                        android.util.Log.d(TAG, "pushFile: DATA #$chunkCount, totalSent=$totalSent")
                    }
                }
            }
            android.util.Log.d(TAG, "pushFile: all DATA sent, totalSent=$totalSent, chunks=$chunkCount")

            // DONE
            val doneBuf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            doneBuf.putInt(0x454e4f44)
            doneBuf.putInt((System.currentTimeMillis() / 1000).toInt())
            stream.write(doneBuf.array(), conn)
            android.util.Log.d(TAG, "pushFile: DONE sent")

            // Read response — must wait for device reply
            val resp = ByteArray(8)
            var totalRead = 0
            while (totalRead < 8) {
                val data = stream.readBlocking(5000) ?: break
                val toCopy = minOf(data.size, 8 - totalRead)
                data.copyInto(resp, totalRead, 0, toCopy)
                totalRead += toCopy
            }
            val respCmd = littleEndianToInt(resp, 0)
            android.util.Log.d(TAG, "pushFile: response totalRead=$totalRead, respCmd=0x${respCmd.toString(16)}")

            conn.closeStream(stream)

            when (respCmd) {
                0x59414b4f -> "推送成功: $remotePath"
                0x4c494146 -> "推送失败"
                else -> "推送完成"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pushFile exception", e)
            "推送异常: ${e.message}"
        }
    }

    /**
     * 与 [AdbRepository.pushFile] 的内存字节版对应：内部落临时文件后走流式推送，
     * 调用方不必自己管临时文件（此前这段逻辑散在 ViewModel 里）。
     */
    suspend fun pushFile(localData: ByteArray, remotePath: String): String = withContext(Dispatchers.IO) {
        val tmp = File(appContext.cacheDir, "wearadb_push_${System.currentTimeMillis()}.tmp")
        try {
            tmp.writeBytes(localData)
            pushFile(tmp, remotePath)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ── 安装 APK ──

    suspend fun installApk(apkData: ByteArray): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB安装APK(ByteArray): size=${apkData.size}")
        android.util.Log.d(TAG, "installApk(ByteArray): size=${apkData.size}")
        try {
            val tmpPath = "/data/local/tmp/_wearadb_install_${System.currentTimeMillis()}.apk"
            // Write bytes to temp file, then push
            val tmpFile = File(appContext.cacheDir, "wearadb_usb_install.apk")
            tmpFile.writeBytes(apkData)
            val pushResult = pushFile(tmpFile, tmpPath)
            tmpFile.delete()
            android.util.Log.d(TAG, "installApk(ByteArray): pushResult=$pushResult")
            if (!pushResult.contains("成功")) return@withContext "推送失败: $pushResult"

            val installResult = executeCommand("pm install -r $tmpPath")
            android.util.Log.d(TAG, "installApk(ByteArray): installResult=$installResult")
            executeCommand("rm -f $tmpPath")

            val clean = installResult.trim()
            when {
                clean.contains("Success") -> "安装成功"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "installApk(ByteArray) exception", e)
            "安装异常: ${e.message}"
        }
    }

    suspend fun installApk(apkFile: File): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB安装APK(File): ${apkFile.name}, size=${apkFile.length()}")
        android.util.Log.d(TAG, "installApk: ${apkFile.name}, size=${apkFile.length()}")
        try {
            val tmpPath = "/data/local/tmp/_wearadb_install_${System.currentTimeMillis()}.apk"
            val pushResult = pushFile(apkFile, tmpPath)
            android.util.Log.d(TAG, "installApk: pushResult=$pushResult")
            if (!pushResult.contains("成功")) return@withContext "推送失败: $pushResult"

            val installResult = executeCommand("pm install -r $tmpPath")
            android.util.Log.d(TAG, "installApk: installResult=$installResult")
            executeCommand("rm -f $tmpPath")

            val clean = installResult.trim()
            when {
                clean.contains("Success") -> "安装成功"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "installApk exception", e)
            "安装异常: ${e.message}"
        }
    }

    // ── Split APK 安装（有线通道）──
    // 与 AdbRepository 的同名方法一一对应：此前 USB 会话下这几个安装入口只走无线仓储，
    // 结果"点安装没反应"（命令发不出去）。三者最终都汇到 installSplitFromLocalFiles。

    /** 本地文件已就绪的 Split 安装公共流程：建临时目录 → 推送 → create → write → commit → 清理 */
    private suspend fun installSplitFromLocalFiles(localFiles: List<Pair<String, File>>): String =
        withContext(Dispatchers.IO) {
            val tmpDir = "/data/local/tmp/_wearadb_split_${System.currentTimeMillis()}"
            android.util.Log.d(TAG, "installSplit: ${localFiles.size} files, tmpDir=$tmpDir")
            try {
                executeCommand("mkdir -p $tmpDir", 5000)
                // 1. 逐个推送（本地文件，避免整包进内存）
                for ((safeName, file) in localFiles) {
                    val pushResult = pushFile(file, "$tmpDir/$safeName")
                    android.util.Log.d(TAG, "installSplit: push $safeName (${file.length()}B) -> $pushResult")
                    if (!pushResult.contains("成功")) {
                        executeCommand("rm -rf $tmpDir", 5000)
                        return@withContext "推送失败 ($safeName): $pushResult"
                    }
                }
                // 2. 创建安装会话（不带 -S，兼容更多设备）
                val createResult = executeCommand("pm install-create", 30000)
                val sessionId = parseInstallSessionId(createResult)
                if (sessionId == null) {
                    executeCommand("rm -rf $tmpDir", 5000)
                    return@withContext "创建会话失败: ${createResult.trim()}"
                }
                // 3. 逐个写入会话
                for ((safeName, _) in localFiles) {
                    val writeResult = executeCommand("pm install-write $sessionId $safeName $tmpDir/$safeName", 60000)
                    android.util.Log.d(TAG, "installSplit: write $safeName -> $writeResult")
                    if (!writeResult.contains("Success") && writeResult.trim().isNotEmpty() &&
                        !writeResult.contains("success", ignoreCase = true)
                    ) {
                        executeCommand("pm install-abandon $sessionId", 5000)
                        executeCommand("rm -rf $tmpDir", 5000)
                        return@withContext "写入失败 ($safeName): ${writeResult.trim()}"
                    }
                }
                // 4. 提交 + 清理
                val commitResult = executeCommand("pm install-commit $sessionId", 60000)
                executeCommand("rm -rf $tmpDir", 5000)
                android.util.Log.d(TAG, "installSplit: commit -> $commitResult")
                val clean = commitResult.trim()
                when {
                    clean.contains("Success") -> "Split APK 安装成功 (${localFiles.size} 个文件)"
                    clean.isEmpty() -> "安装失败: 无响应"
                    else -> "安装失败: $clean"
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "installSplit exception", e)
                runCatching { executeCommand("rm -rf $tmpDir", 5000) }
                "安装异常: ${e.message}"
            }
        }

    /** Split APK（内存字节版）：落临时文件后走公共流程 */
    suspend fun installSplitApk(apkFiles: List<Pair<String, ByteArray>>): String = withContext(Dispatchers.IO) {
        val temps = mutableListOf<File>()
        try {
            val local = apkFiles.mapIndexed { index, (name, data) ->
                val f = File(appContext.cacheDir, "wearadb_split_${System.currentTimeMillis()}_$index.apk")
                f.writeBytes(data)
                temps += f
                safeSplitName(name) to f
            }
            installSplitFromLocalFiles(local)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "installSplitApk exception", e)
            "安装异常: ${e.message}"
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    /** Split APK（本地文件版）：直接推送，无内存拷贝 */
    suspend fun installSplitApkFiles(apkFiles: List<Pair<String, File>>): String =
        installSplitFromLocalFiles(apkFiles.map { (name, file) -> safeSplitName(name) to file })

    /**
     * .apks（zip）安装：逐个条目解压到临时文件 → 推送 → install-write。
     * 流式解压，不会把整包加载进内存（与无线实现保持同样的语义与文案）。
     */
    suspend fun installSplitApkFromApksFile(apksFile: File): String = withContext(Dispatchers.IO) {
        val temps = mutableListOf<File>()
        try {
            val local = mutableListOf<Pair<String, File>>()
            java.util.zip.ZipInputStream(apksFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        val safe = safeSplitName(entry.name)
                        val f = File(appContext.cacheDir, "wearadb_apks_${System.currentTimeMillis()}_$safe")
                        f.outputStream().use { out -> zip.copyTo(out) }
                        if (f.length() > 0) {
                            temps += f
                            local += safe to f
                        } else {
                            runCatching { f.delete() }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            if (local.isEmpty()) return@withContext ".apks 中未找到有效 APK 文件"
            installSplitFromLocalFiles(local)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "installSplitApkFromApksFile exception", e)
            "安装异常: ${e.message}"
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    private fun safeSplitName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "split.apk" }

    private fun parseInstallSessionId(output: String): String? =
        Regex("sessionId\\s*[=:]?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(output)?.groupValues?.get(1)
            ?: Regex("(\\d+)").find(output.trim())?.groupValues?.get(1)

    // ── 应用管理（有线通道）──
    // 与 AdbRepository 同名方法一一对应：USB 会话下 ViewModel 走这里，避免操作静默落空。

    suspend fun uninstallApp(pkg: String, keepData: Boolean = false): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB卸载${if (keepData) "(保留数据)" else ""}: pkg=$pkg")
        executeCommand(if (keepData) "pm uninstall -k $pkg" else "pm uninstall $pkg", 30000)
    }

    suspend fun uninstallAppKeepData(pkg: String): String = uninstallApp(pkg, keepData = true)

    suspend fun clearAppData(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB清除数据: pkg=$pkg")
        executeCommand("pm clear $pkg", 30000)
    }

    suspend fun forceStopApp(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB强制停止: pkg=$pkg")
        executeCommand("am force-stop $pkg", 15000)
    }

    suspend fun disableApp(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB禁用: pkg=$pkg")
        executeCommand("pm disable-user $pkg", 15000)
    }

    suspend fun enableApp(pkg: String): String = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB启用: pkg=$pkg")
        executeCommand("pm enable $pkg", 15000)
    }

    /** `pm path <pkg>` → 远程 APK 路径列表（base 在前，split 在后）；无输出返回空列表 */
    suspend fun packageApkPaths(pkg: String): List<String> = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB查询安装包路径: pkg=$pkg")
        parseApkPaths(executeCommand("pm path $pkg", 15000))
    }

    /** 远程文件字节数（`stat -c %s`）；取不到返回 -1（调用方退化为未知总量进度） */
    suspend fun remoteFileSize(path: String): Long = withContext(Dispatchers.IO) {
        executeCommand("stat -c %s '$path'", 8000).trim().toLongOrNull() ?: -1L
    }

    /**
     * 流式拉取远程文件到 [sink]（SYNC RECV，DATA 块直接写出）。
     *
     * 与 [pullFile] 的关键区别：**不把文件缓存在内存里**。pullFile 用 ByteArrayOutputStream
     * 累积整份数据，提取 250MB 级 APK 时扩容要一次申请 256MB → OutOfMemoryError 崩溃（真机已复现）。
     * 这里只保留一个不超过单帧的残余缓冲，内存占用与文件大小无关。
     *
     * @return true 表示收到 DONE（数据完整）
     */
    suspend fun pullTo(remotePath: String, sink: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB流式拉取: remotePath=$remotePath")
        val conn = awaitManager().getConnection() ?: return@withContext false
        try {
            val stream = conn.openSync()
            try {
                val pathBytes = remotePath.toByteArray()
                val recvBuf = java.nio.ByteBuffer.allocate(8 + pathBytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                recvBuf.putInt(0x56434552) // RECV
                recvBuf.putInt(pathBytes.size)
                recvBuf.put(pathBytes)
                stream.write(recvBuf.array(), conn)

                // readBlocking 返回的字节流与协议帧不对齐：这里只缓存"还没消费完"的残余，
                // 上限受单次读取长度约束（≤ 数十 KB），不会随文件增长。
                var carry = ByteArray(0)
                fun take(n: Int): ByteArray? {
                    while (carry.size < n) {
                        val chunk = stream.readBlocking(10000) ?: return null
                        carry = if (carry.isEmpty()) chunk else carry + chunk
                    }
                    val out = carry.copyOfRange(0, n)
                    carry = if (carry.size > n) carry.copyOfRange(n, carry.size) else ByteArray(0)
                    return out
                }

                var totalWritten = 0L
                var ok = false
                while (true) {
                    val header = take(8) ?: break
                    val cmd = littleEndianToInt(header, 0)
                    val size = littleEndianToInt(header, 4)
                    when (cmd) {
                        0x41544144 -> { // DATA
                            var remaining = size
                            while (remaining > 0) {
                                val data = take(minOf(remaining, 64 * 1024)) ?: return@withContext false
                                sink.write(data)
                                totalWritten += data.size
                                remaining -= data.size
                            }
                        }
                        0x454e4f44 -> { ok = true; break }   // DONE
                        0x4c494146 -> break                  // FAIL
                        else -> break
                    }
                }
                sink.flush()
                android.util.Log.d(TAG, "pullTo: written=$totalWritten, ok=$ok")
                ok
            } finally {
                runCatching { conn.closeStream(stream) }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pullTo exception", e)
            false
        }
    }

    // ── 文件管理 ──

    suspend fun listFiles(path: String): List<com.wearadb.data.model.FileEntry> = withContext(Dispatchers.IO) {
        val output = executeCommand("ls -Lla $path 2>&1")
        android.util.Log.d(TAG, "listFiles($path) output length=${output.length}")
        com.wearadb.adb.AdbOutputParser.parseFileListing(output, path)
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        executeCommand("cat $path")
    }

    suspend fun deleteFile(path: String): String = withContext(Dispatchers.IO) {
        executeCommand("rm -rf $path")
    }

    suspend fun createDirectory(path: String): String = withContext(Dispatchers.IO) {
        executeCommand("mkdir -p $path")
    }

    suspend fun pullFile(remotePath: String): PullResult = withContext(Dispatchers.IO) {
        WearAdbLogger.i("UsbAdb", "USB拉取文件: remotePath=$remotePath")
        android.util.Log.d(TAG, "pullFile: $remotePath")
        val conn = awaitManager().getConnection()
            ?: return@withContext PullResult(false, null, "USB 未连接")
        try {
            val stream = conn.openSync()

            // RECV header — single buffer
            val pathBytes = remotePath.toByteArray()
            val recvBuf = java.nio.ByteBuffer.allocate(8 + pathBytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            recvBuf.putInt(0x56434552) // RECV
            recvBuf.putInt(pathBytes.size)
            recvBuf.put(pathBytes)
            stream.write(recvBuf.array(), conn)
            android.util.Log.d(TAG, "pullFile: RECV sent, ${recvBuf.array().size} bytes")

            // Buffered reader: WRTE payloads → continuous byte stream
            val dataBuffer = java.io.ByteArrayOutputStream()
            var totalReceived = 0L
            var dataOffset = 0 // tracks consumed bytes in dataBuffer

            fun refillBuffer(): Boolean {
                // Discard consumed bytes
                if (dataOffset > 0) {
                    val remaining = dataBuffer.toByteArray().copyOfRange(dataOffset, dataBuffer.size())
                    dataBuffer.reset()
                    dataBuffer.write(remaining)
                    dataOffset = 0
                }
                // Read more WRTE payloads until we have enough
                while (dataBuffer.size() - dataOffset < 8) {
                    val chunk = stream.readBlocking(10000) ?: return false
                    dataBuffer.write(chunk)
                }
                return true
            }

            fun readBytes(n: Int): ByteArray? {
                // Ensure buffer has enough data, refill if needed
                while (dataBuffer.size() - dataOffset < n) {
                    val chunk = stream.readBlocking(10000) ?: return null
                    dataBuffer.write(chunk)
                }
                val buf = dataBuffer.toByteArray()
                val result = buf.copyOfRange(dataOffset, dataOffset + n)
                dataOffset += n
                return result
            }

            // Read SYNC DATA chunks
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                if (!refillBuffer()) break
                val headerBytes = readBytes(8) ?: break
                val cmd = littleEndianToInt(headerBytes, 0)
                val size = littleEndianToInt(headerBytes, 4)
                android.util.Log.d(TAG, "pullFile: cmd=0x${cmd.toString(16)}, size=$size")

                when (cmd) {
                    0x41544144 -> { // DATA
                        var remaining = size
                        while (remaining > 0) {
                            val chunk = readBytes(remaining) ?: break
                            output.write(chunk)
                            totalReceived += chunk.size
                            remaining -= chunk.size
                        }
                        if (totalReceived % (1024 * 1024) < 65536) {
                            android.util.Log.d(TAG, "pullFile: received ${totalReceived / 1024}KB")
                        }
                    }
                    0x454e4f44 -> { // DONE
                        android.util.Log.d(TAG, "pullFile: DONE, totalReceived=$totalReceived")
                        break
                    }
                    0x4c494146 -> { // FAIL
                        android.util.Log.w(TAG, "pullFile: FAIL")
                        conn.closeStream(stream)
                        return@withContext PullResult(false, null, "拉取失败: $remotePath")
                    }
                    else -> break
                }
            }

            conn.closeStream(stream)
            android.util.Log.d(TAG, "pullFile: success, ${output.size()} bytes")
            PullResult(true, output.toByteArray(), "拉取成功: $remotePath")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pullFile exception", e)
            PullResult(false, null, "拉取异常: ${e.message}")
        }
    }

    // ── 通用设备操作（有线通道）──
    // 与 AdbRepository 同名同语义：ViewModel 两侧调用形状完全一致，
    // 避免出现"只给无线加了新能力、有线忘了补"的静默缺失。

    /** 带日志的通用命令执行（对应无线侧各操作里的 WearAdbLogger 记录） */
    private suspend fun loggedOp(label: String, command: String): String {
        WearAdbLogger.i("UsbAdb", "USB$label")
        return executeCommand(command)
    }

    suspend fun reboot(): String = loggedOp("重启设备", "reboot")
    suspend fun rebootRecovery(): String = loggedOp("重启到Recovery", "reboot recovery")
    suspend fun rebootBootloader(): String = loggedOp("重启到Bootloader", "reboot bootloader")
    suspend fun shutdown(): String = loggedOp("关机", "reboot -p")

    suspend fun tap(x: Int, y: Int): String = executeCommand("input tap $x $y")
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300): String =
        executeCommand("input swipe $x1 $y1 $x2 $y2 $dur")
    suspend fun keyEvent(code: Int): String = executeCommand("input keyevent $code")
    suspend fun inputText(text: String): String = executeCommand("input text \"$text\"")

    suspend fun enableWifi(): String = loggedOp("开启WiFi", "svc wifi enable")
    suspend fun disableWifi(): String = loggedOp("关闭WiFi", "svc wifi disable")
    suspend fun enableBluetooth(): String = loggedOp("开启蓝牙", "svc bluetooth enable")
    suspend fun disableBluetooth(): String = loggedOp("关闭蓝牙", "svc bluetooth disable")

    suspend fun volumeUp(): String = executeCommand("input keyevent 24")
    suspend fun volumeDown(): String = executeCommand("input keyevent 25")
    suspend fun volumeMute(): String = executeCommand("input keyevent 164")
    suspend fun screenOn(): String = executeCommand("input keyevent 26")
    suspend fun screenOff(): String = executeCommand("input keyevent 26")

    private fun littleEndianToInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun destroy() {
        mirrorEngine.destroy()
        try { initDeferred.getCompleted().manager.destroy() } catch (_: Exception) {}
    }

    // ── Key management ──

    private fun loadOrGenerateKeyPair(): Pair<PrivateKey, Certificate> {
        val pk = readPrivateKey()
        val cert = readCertificate()
        if (pk != null && cert != null) return pk to cert

        // Generate new key pair
        val keyPairGen = java.security.KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, java.security.SecureRandom.getInstance("SHA1PRNG"))
        val keyPair = keyPairGen.generateKeyPair()
        val newPk = keyPair.private
        val newCert = generateSelfSignedCert(keyPair.public, newPk)

        writePrivateKey(newPk)
        writeCertificate(newCert)
        return newPk to newCert
    }

    private fun generateSelfSignedCert(publicKey: java.security.PublicKey, privateKey: PrivateKey): java.security.cert.Certificate {
        val subject = javax.security.auth.x500.X500Principal("CN=wear-adb-usb")
        val serial = java.math.BigInteger.ONE
        val notBefore = java.util.Date()
        val notAfter = java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, publicKey
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
        val holder = builder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun readPrivateKey(): PrivateKey? {
        val file = File(appContext.filesDir, "adb_usb_private.key")
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Exception) { null }
    }

    private fun writePrivateKey(key: PrivateKey) {
        File(appContext.filesDir, "adb_usb_private.key").writeBytes(key.encoded)
    }

    private fun readCertificate(): java.security.cert.Certificate? {
        val file = File(appContext.filesDir, "adb_usb_cert.pem")
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } catch (_: Exception) { null }
    }

    private fun writeCertificate(cert: java.security.cert.Certificate) {
        val file = File(appContext.filesDir, "adb_usb_cert.pem")
        FileOutputStream(file).use { os ->
            os.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
            os.write(android.util.Base64.encode(cert.encoded, android.util.Base64.DEFAULT))
            os.write("\n-----END CERTIFICATE-----\n".toByteArray())
        }
    }
}

enum class UsbAdbConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
