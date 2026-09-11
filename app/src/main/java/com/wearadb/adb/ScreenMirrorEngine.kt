package com.wearadb.adb

import android.content.Context
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 屏幕查看状态机：Idle → Starting（推送/启动/连接）→ Streaming；任意态可入 Error。 */
sealed class MirrorStatus {
    object Idle : MirrorStatus()
    object Starting : MirrorStatus()
    object Streaming : MirrorStatus()
    data class Error(val message: String) : MirrorStatus()
}

/**
 * 镜像会话参数。
 * maxSize：scrcpy max_size（服务端按 8 的倍数取整），0 = 不限制；
 * bitRate：video_bit_rate（bps）；maxFps：max_fps，0 = 不限制；
 * turnOffScreen：投屏时熄灭设备屏幕（SET_SCREEN_POWER_MODE 控制消息，需要 control 通道）；
 * stayAwake：保持设备唤醒（scrcpy stay_awake）。声音恒不转发（audio=false 固定）。
 */
data class MirrorOptions(
    val maxSize: Int = 0,
    val bitRate: Int = 4_000_000,
    val maxFps: Float = 0f,
    val turnOffScreen: Boolean = false,
    val stayAwake: Boolean = false,
)

/**
 * 通道无关的镜像流：统一 USB 自研 UsbAdbStream 与无线 libadb-android AdbStream 的读写语义。
 * readBlocking 返回 null：超时无数据（用 isClosed 区分断流）或流已关闭。
 * writeBytes 仅 control 流使用（熄屏/恢复消息）。
 */
interface MirrorStream {
    fun readBlocking(timeoutMs: Long): ByteArray?
    fun writeBytes(data: ByteArray): Boolean
    val isClosed: Boolean
    val isOpen: Boolean
    fun close()
}

/** 镜像会话所需的通道原语（USB / 无线各自适配；均在 IO 上下文调用）。 */
interface MirrorTransport {
    suspend fun executeCommand(cmd: String): String
    suspend fun pushFileTo(localFile: File, remotePath: String): Boolean
    suspend fun openShellStream(cmd: String): MirrorStream

    /** 单次连接抽象套接字；不可达返回 null（重试由 engine 负责）。 */
    suspend fun openAbstractSocket(dest: String): MirrorStream?
}

/** USB 通道流适配（UsbAdbStream.write 需要父连接引用）。 */
internal class UsbMirrorStream(
    private val stream: UsbAdbStream,
    private val conn: UsbAdbConnection
) : MirrorStream {
    override fun readBlocking(timeoutMs: Long): ByteArray? = stream.readBlocking(timeoutMs)

    override fun writeBytes(data: ByteArray): Boolean = try {
        stream.write(data, conn); true
    } catch (e: Exception) {
        false
    }

    override val isClosed: Boolean get() = stream.isClosed
    override val isOpen: Boolean get() = stream.isOpen
    override fun close() {
        try { stream.close() } catch (_: Exception) {}
    }
}

/**
 * 通道无关的 scrcpy-server 镜像会话引擎（USB / 无线 adb 共用）。
 *
 * 数据流：
 *   push assets jar → app_process 启动 scrcpy-server(tunnelForward, 监听抽象套接字 "scrcpy")
 *     → openStream("localabstract:scrcpy") 连 video（server accept 顺序：video → control）
 *     → 连 control（control=true 时必须连，server 才会开始发视频头；熄屏消息走此通道）
 *     → 读 12B 视频头 [codec_id:4][w:4][h:4] → 首个 CONFIG 包作 csd-0 配置 MediaCodec
 *     → 循环读 12B meta 包头 + 裸包 → ScreenMirrorDecoder 解码渲染到 Surface
 *
 * 线协议参考（scrcpy v2.7 Streamer.writeVideoHeader / writeFrameMeta / demuxer.c）：
 *   视频头: [codec_id:4 BE][width:4 BE][height:4 BE]
 *   每包:   [pts_flags:8 BE][len:4 BE] + len 字节裸包（pts_flags bit63=CONFIG, bit62=KEY_FRAME）
 *   控制消息: [type:1][payload]；SET_SCREEN_POWER_MODE=10 + mode 1 字节（0=OFF, 2=NORMAL）
 */
class ScreenMirrorEngine(private val appContext: Context) {

    companion object {
        private const val TAG = "ScreenMirrorEngine"
        private const val SCRCPY_VERSION = "2.7"
        private const val SCRCPY_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SCRCPY_ASSET_PATH = "scrcpy/scrcpy-server.jar"
        private const val SCRCPY_ABSTRACT_DEST = "localabstract:scrcpy"
        private const val CODEC_ID_H264 = 0x68323634
        private const val SC_PACKET_HEADER_SIZE = 12
        private const val SC_PACKET_FLAG_CONFIG = 1L shl 63
        private const val SC_PACKET_PTS_MASK = (1L shl 62) - 1

        /** scrcpy 2.7 ControlMessage.TYPE_SET_SCREEN_POWER_MODE */
        private const val CTRL_TYPE_SET_SCREEN_POWER_MODE = 10

        /** SurfaceControl.POWER_MODE_OFF / POWER_MODE_NORMAL（Device.setScreenPowerMode 原样透传） */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<MirrorStatus>(MirrorStatus.Idle)
    val status: StateFlow<MirrorStatus> = _status.asStateFlow()

    /** 编码分辨率（scrcpy 视频头），触摸映射用 */
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    /** 设备真实分辨率（wm size），input tap 坐标系用 */
    private val _realSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val realSize: StateFlow<Pair<Int, Int>?> = _realSize.asStateFlow()

    private var job: Job? = null
    private var launchStream: MirrorStream? = null
    private var videoStream: MirrorStream? = null
    private var controlStream: MirrorStream? = null

    @Volatile
    private var running = false

    /**
     * 安全启动（画质/帧率/开关变更、重试均走此入口）：
     * 先停旧会话并 join 等其完全退场（旧任务 finally 会清理共享流/复位状态，
     * 与新会话重叠会误杀——join 线性化消除竞态），再起新会话。
     * join 上限 3s 兜底（读循环被关流 sentinel 即时唤醒，正常远快于此）。
     */
    suspend fun startSafe(transport: MirrorTransport, surface: Surface, opts: MirrorOptions) {
        stop()
        try { withTimeoutOrNull(3000) { job?.join() } } catch (_: Exception) {}
        job = scope.launch { runLoop(transport, surface, opts) }
    }

    /** 停止投屏并释放资源（幂等）。屏幕恢复由会话 finally 负责。 */
    fun stop() {
        running = false
        // 保留 job 引用（不置 null）：startSafe 需要 join 等旧任务真正退场
        job?.cancel()
        try { videoStream?.close() } catch (_: Exception) {}
        try { controlStream?.close() } catch (_: Exception) {}
        try { launchStream?.close() } catch (_: Exception) {}
        videoStream = null
        controlStream = null
        launchStream = null
        _status.value = MirrorStatus.Idle
    }

    /** 彻底销毁（repository destroy 时调用）。 */
    fun destroy() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop(transport: MirrorTransport, surface: Surface, opts: MirrorOptions) {
        running = true
        _status.value = MirrorStatus.Starting
        var launchS: MirrorStream? = null
        var videoS: MirrorStream? = null
        var controlS: MirrorStream? = null
        var screenTurnedOff = false
        val decoder = ScreenMirrorDecoder()
        try {
            // 0. 清理残留 server（上次异常退出会占住抽象套接字名）
            transport.executeCommand("pkill -f com.genymobile.scrcpy.Server 2>/dev/null; true")
            delay(200)

            // 1. 推送 server jar（已存在则跳过）
            if (!pushServerIfNeeded(transport)) {
                _status.value = MirrorStatus.Error("推送 scrcpy-server.jar 失败")
                return
            }

            // 2. 设备真实分辨率（触摸映射坐标系）
            _realSize.value = parseWmSize(transport.executeCommand("wm size"))

            // 3. 启动 server。control=true：control socket 必须连接（server 才会开始发视频头，
            //    熄屏也走此通道）；clipboard_autosync=false 抑制控制流上的设备消息；
            //    声音恒不转发（audio=false）；send_*_meta=false 使视频流直接从 12B codec 头开始。
            val launchCmd = buildString {
                append("CLASSPATH=$SCRCPY_REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SCRCPY_VERSION")
                append(" scid=-1 log_level=info video=true audio=false control=true tunnel_forward=true cleanup=false")
                append(" video_codec=h264 max_size=${opts.maxSize} video_bit_rate=${opts.bitRate} max_fps=${opts.maxFps}")
                append(" send_device_meta=false send_dummy_byte=false send_frame_meta=true send_codec_meta=true")
                append(" clipboard_autosync=false")
                if (opts.stayAwake) append(" stay_awake=true")
            }
            launchS = transport.openShellStream(launchCmd)
            launchStream = launchS
            startLaunchLogDrain(launchS)

            // 4. 连接 video 抽象套接字（server 启动需数百 ms，短超时+重试）
            var v: MirrorStream? = null
            repeat(20) {
                if (!running) return
                val s = transport.openAbstractSocket(SCRCPY_ABSTRACT_DEST)
                if (s != null && s.isOpen) {
                    v = s
                    return@repeat
                }
                try { s?.close() } catch (_: Exception) {}
                delay(400)
            }
            val video = v
            if (video == null) {
                _status.value = MirrorStatus.Error("连接 scrcpy-server 失败（server 未就绪或不支持）")
                return
            }
            videoS = video
            videoStream = video

            // 5. 连接 control 套接字（server accept 顺序 video → control，必须成功）
            var c: MirrorStream? = null
            repeat(20) {
                if (!running) return
                val s = transport.openAbstractSocket(SCRCPY_ABSTRACT_DEST)
                if (s != null && s.isOpen) {
                    c = s
                    return@repeat
                }
                try { s?.close() } catch (_: Exception) {}
                delay(400)
            }
            val control = c
            if (control == null) {
                _status.value = MirrorStatus.Error("连接 scrcpy-server 控制通道失败")
                return
            }
            controlS = control
            controlStream = control

            // 6. 熄屏（与官方客户端一致：连接建立后异步发 SET_SCREEN_POWER_MODE）
            if (opts.turnOffScreen) {
                screenTurnedOff = control.writeBytes(
                    byteArrayOf(CTRL_TYPE_SET_SCREEN_POWER_MODE.toByte(), POWER_MODE_OFF.toByte())
                )
                if (!screenTurnedOff) Log.w(TAG, "熄屏消息发送失败（继续投屏）")
            }

            // 7. 读循环：统一累积器 → 12B 头/包 + 定长 payload
            _status.value = MirrorStatus.Streaming
            val acc = ByteAccumulator()
            var pendingMeta: ByteArray? = null
            var pendingLen = 0
            var headerRead = false
            var encW = 0
            var encH = 0

            while (running && video.isOpen) {
                val chunk = video.readBlocking(5000)
                if (chunk == null) {
                    // 超时无数据（静止画面长时间无新帧）≠ 断流；仅流关闭才退出
                    if (video.isClosed) break else continue
                }
                acc.append(chunk)

                var progress = true
                while (progress) {
                    progress = false

                    if (!headerRead) {
                        val head = acc.tryRead(12) ?: break
                        val codecId = readIntBE(head, 0)
                        when {
                            codecId == 0 -> {
                                _status.value = MirrorStatus.Error("设备禁用了视频流"); return
                            }
                            codecId == 1 -> {
                                _status.value = MirrorStatus.Error("设备视频配置错误"); return
                            }
                            codecId != CODEC_ID_H264 -> {
                                _status.value = MirrorStatus.Error(String.format("不支持的编码: 0x%08X", codecId)); return
                            }
                        }
                        encW = readIntBE(head, 4)
                        encH = readIntBE(head, 8)
                        if (encW <= 0 || encH <= 0) {
                            _status.value = MirrorStatus.Error("非法视频尺寸 ${encW}x${encH}"); return
                        }
                        _videoSize.value = encW to encH
                        headerRead = true
                        progress = true
                        continue
                    }

                    val meta = pendingMeta
                    if (meta == null) {
                        val m = acc.tryRead(SC_PACKET_HEADER_SIZE) ?: break
                        val plen = readIntBE(m, 8)
                        if (plen <= 0 || plen > 16 * 1024 * 1024) {
                            _status.value = MirrorStatus.Error("非法包长度: $plen"); return
                        }
                        pendingMeta = m
                        pendingLen = plen
                        progress = true
                        continue
                    } else {
                        val payload = acc.tryRead(pendingLen) ?: break
                        val ptsFlags = readLongBE(meta, 0)
                        val isConfig = (ptsFlags and SC_PACKET_FLAG_CONFIG) != 0L
                        val pts = ptsFlags and SC_PACKET_PTS_MASK
                        if (isConfig) {
                            // 首个 CONFIG 包（SPS+PPS, Annex-B）作为 csd-0 配置解码器
                            if (!decoder.isConfigured) {
                                if (!decoder.configure(surface, encW, encH, payload)) {
                                    _status.value = MirrorStatus.Error("解码器初始化失败"); return
                                }
                            }
                            // 已配置后的重复 CONFIG（罕见）忽略：SPS/PPS 已生效
                        } else {
                            decoder.feed(payload, false, pts)
                        }
                        pendingMeta = null
                        progress = true
                    }
                }
            }
        } catch (e: CancellationException) {
            // 正常停止
        } catch (e: Exception) {
            if (running) {
                _status.value = MirrorStatus.Error("投屏中断: ${e.message}")
            }
        } finally {
            withContext(NonCancellable) {
                // 恢复设备屏幕（与官方客户端退出行为一致；WAKEUP keyevent 兜底）
                if (screenTurnedOff) {
                    try {
                        controlS?.writeBytes(
                            byteArrayOf(CTRL_TYPE_SET_SCREEN_POWER_MODE.toByte(), POWER_MODE_NORMAL.toByte())
                        )
                    } catch (_: Exception) {}
                }
                try { videoS?.close() } catch (_: Exception) {}
                try { controlS?.close() } catch (_: Exception) {}
                try { launchS?.close() } catch (_: Exception) {}
                videoStream = null
                controlStream = null
                launchStream = null
                decoder.release()
                running = false
                if (screenTurnedOff) {
                    try { transport.executeCommand("input keyevent 224") } catch (_: Exception) {}
                }
                val cur = _status.value
                if (cur == MirrorStatus.Streaming || cur == MirrorStatus.Starting) {
                    _status.value = MirrorStatus.Idle
                }
            }
        }
    }

    /**
     * 消费 server 的 stdout/stderr（log_level=info 有持续输出）。
     * 不排空会填满 adbd 缓冲导致 server 写阻塞；同时保留日志便于诊断。
     */
    private fun startLaunchLogDrain(stream: MirrorStream) {
        Thread({
            while (!stream.isClosed) {
                val d = try { stream.readBlocking(5000) } catch (_: Exception) { break }
                if (d == null) {
                    if (stream.isClosed) break else continue
                }
                if (d.isNotEmpty()) {
                    Log.d(TAG, "scrcpy-server: ${String(d).trim().take(400)}")
                }
            }
        }, "ScrcpyServerDrain").apply { isDaemon = true; start() }
    }

    /** assets jar → 缓存文件 → push（已存在则跳过）。 */
    private suspend fun pushServerIfNeeded(transport: MirrorTransport): Boolean {
        return try {
            val ls = transport.executeCommand("ls -l $SCRCPY_REMOTE_PATH 2>/dev/null")
            if (ls.contains(SCRCPY_REMOTE_PATH)) return true
            val tmp = File(appContext.cacheDir, "scrcpy-server.jar")
            appContext.assets.open(SCRCPY_ASSET_PATH).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val ok = transport.pushFileTo(tmp, SCRCPY_REMOTE_PATH)
            tmp.delete()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "pushScrcpyServer failed: ${e.message}", e)
            false
        }
    }

    /** 解析 `wm size` 输出，Override 优先于 Physical。 */
    private fun parseWmSize(out: String): Pair<Int, Int>? {
        val override = Regex("Override size:\\s*(\\d+)x(\\d+)").find(out)
        val physical = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(out)
        val m = override ?: physical ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        return w to h
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)

    private fun readLongBE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /**
     * 跨 chunk 的字节累积器。tryRead 语义：够 n 字节则取出并前移，否则返回 null 等待更多。
     * scrcpy 包边界与传输层分包不对齐，必须经此缓冲。
     */
    private class ByteAccumulator {
        private var buf = ByteArray(256 * 1024)
        private var len = 0

        fun append(data: ByteArray) {
            if (data.isEmpty()) return
            if (len + data.size > buf.size) {
                var cap = buf.size
                while (cap < len + data.size) cap *= 2
                buf = buf.copyOf(cap)
            }
            data.copyInto(buf, len)
            len += data.size
        }

        fun tryRead(n: Int): ByteArray? {
            if (len < n || n <= 0) return null
            val out = buf.copyOfRange(0, n)
            System.arraycopy(buf, n, buf, 0, len - n)
            len -= n
            return out
        }
    }
}
