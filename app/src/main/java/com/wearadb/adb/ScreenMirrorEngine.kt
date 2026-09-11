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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /**
     * **启动期**命令（批量并发调用场景使用）。
     *
     * 存在意义：引擎在启动阶段会并行发起「推送 server jar」与「取 wm size」以省掉
     * 一次串行往返。USB 通道是自研实现、可安全并发；无线侧的 libadb-android 3.1.1
     * 则**不是线程安全的**（`AdbConnection.open()` 里 `++mLastLocalId` 用的是普通
     * int，并发会拿到重复 localId）。因此无线适配层把这两个方法实现为"经互斥闸门
     * 串行"，USB 适配层保持默认直通。
     *
     * 默认实现 = 直通（USB / 其它线程安全通道无需覆写）。
     */
    suspend fun executeCommandSerialized(cmd: String): String = executeCommand(cmd)

    /** 见 [executeCommandSerialized]；默认直通。 */
    suspend fun pushFileToSerialized(localFile: File, remotePath: String): Boolean =
        pushFileTo(localFile, remotePath)

    /**
     * 单次连接抽象套接字。
     * @param timeoutMs 等待设备 OKAY 的超时（套接字未就绪时通常回 CLSE，此时同样按
     *        超时上界耗时——见 UsbAdbStream.waitForOpen 的唤醒条件），
     *        调用方用短超时 + 多次重试代替长超时单次等待。
     * @return 不可达返回 null（重试由 engine 负责）。
     */
    suspend fun openAbstractSocket(dest: String, timeoutMs: Long = 2000L): MirrorStream?
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
     *   push assets jar → app_process 启动 scrcpy-server(tunnelForward, 监听抽象套接字 "scrcpy_<scid>")
     *     → openStream("localabstract:scrcpy_<scid>") 连 video（server accept 顺序：video → control）
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
        private const val CODEC_ID_H264 = 0x68323634

        /**
         * 抽象套接字名格式（scrcpy v2.x 官方约定）。
         *
         * 官方 Server 用 `scid` 生成套接字名 `scrcpy_%08x`，客户端必须推导出同一名字
         * 才能连上。此前本项目固定 `scid=-1` 且启动前 `pkill` 旧 server——那是为了
         * 规避"旧进程占住套接字名"的权宜之计，代价是每次启动都白等 200ms、并且
         * 一旦 pkill 没权限/没匹配到，新 server 会因名字被占直接失败。
         *
         * 现在改为**每次会话随机生成 scid**（与官方客户端 `sc_server_init` 一致：
         * 随机 scid → 随机套接字名），旧 server 各自监听自己的名字互不干扰，
         * 因此既不需要 pkill、也不需要等待。
         *
         * 注意：scrcpy 出于安全考虑，要求 scid 的**高 8 bit 不全为 0**（否则
         * 与旧版本的固定名兼容路径冲突），故取 31 bit 随机数后强制置位 bit24。
         */
        private const val SCRCPY_SOCKET_NAME_FORMAT = "scrcpy_%08x"

        /**
         * `scid=` 命令行参数格式。
         *
         * 必须与 [SCRCPY_SOCKET_NAME_FORMAT] 的数值部分同源：server 侧 `Options.parse`
         * 用 `Integer.parseInt(value, 16)` 解析该参数，再以 `%08x` 拼出监听的套接字名。
         */
        private const val SCRCPY_SCID_FORMAT = "%08x"

        /** scrcpy `SCRCPY_SOCKET_NAME_PREFIX` + 8 位十六进制 scid。 */
        private fun abstractDestFor(scid: Int): String =
            "localabstract:" + String.format(SCRCPY_SOCKET_NAME_FORMAT, scid)

        /**
         * 生成 `scid=` 命令行参数值（8 位十六进制）。
         *
         * **必须传十六进制**：server 侧 `Options.parse` 用 radix 16 解析该参数，再用同一数值
         * 以 `%08x` 拼出监听的套接字名。若此处传十进制字面量，server 解析出的数值与客户端
         * [abstractDestFor] 使用的数值不同，两端名字错位——客户端去连 `scrcpy_01b0ae4d`，
         * 而 server 实际监听 `scrcpy_28354765`，表现为连接重试全部失败（`socket NOT ready`）。
         */
        private fun scidArg(scid: Int): String = String.format(SCRCPY_SCID_FORMAT, scid)
        private const val SC_PACKET_HEADER_SIZE = 12
        private const val SC_PACKET_FLAG_CONFIG = 1L shl 63
        private const val SC_PACKET_PTS_MASK = (1L shl 62) - 1

        /**
         * 抽象套接字就绪探测参数。参考官方 scrcpy `sc_server_connect_to()`
         * （attempts=100 / delay=100ms）：探测粒度要细、总预算要够。
         * 本项目的失败代价是"每次失败 = openTimeoutMs 打满"（adbd 对不存在的
         * localabstract 回 CLSE，而等待闩锁只在 OKAY 时唤醒），因此用
         * 10 × (250+100) ≈ 3.5s 预算覆盖 server 启动的数百 ms，且失败代价可控。
         */
        private const val SOCKET_CONNECT_ATTEMPTS = 10
        private const val SOCKET_OPEN_TIMEOUT_MS = 250L
        private const val SOCKET_RETRY_DELAY_MS = 100L

        /**
         * 控制通道单次写入的有界等待上限。
         *
         * 与 USB 自研栈的 `WRITE_FLOW_CONTROL_TIMEOUT_MS`（1500ms）对齐：正常链路一次
         * 往返只有几毫秒，超过该值基本可判定链路已背压或半死。超时语义不是"重试"而是
         * **判定控制通道不可用**，让调用方改走 `input` 命令回退路径。
         */
        private const val CONTROL_WRITE_TIMEOUT_MS = 1500L

        /** 控制写专用单线程（守护线程：即使卡住也不阻塞进程退出）。 */
        private fun newControlWriteExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "ScrcpyControlWrite").apply { isDaemon = true }
            }

        /** scrcpy 2.7 ControlMessage.TYPE_SET_SCREEN_POWER_MODE */
        private const val CTRL_TYPE_SET_SCREEN_POWER_MODE = 10

        /** SurfaceControl.POWER_MODE_OFF / POWER_MODE_NORMAL（Device.setScreenPowerMode 原样透传） */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        /**
         * 生成会话 scid（随机、非 -1）。
         * scrcpy 要求 scid != -1 且高 8 bit 不为 0，这里取 31 bit 随机数 + 强制 bit24。
         */
        private fun newScid(): Int {
            val r = java.util.Random().nextInt(0x7FFFFFFF)
            return r or (1 shl 24)
        }
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
     * 控制写专用单线程。见 [sendControl] 的说明：libadb 的 `AdbStream.write()` 是
     * **无超时的同步阻塞**，必须由独立线程承载，并在引擎层加有界等待。
     */
    private var controlWriteExecutor = newControlWriteExecutor()

    /** 上一笔控制写是否仍未返回（链路背压时避免无限堆积待执行任务）。 */
    @Volatile
    private var controlWriteInFlight = false

    /** 控制通道健康度：写超时/失败后置 false，下一次会话启动时复位。 */
    @Volatile
    private var controlHealthy = true

    /**
     * 安全启动（画质/帧率/开关变更、重试均走此入口）：
     * 先停旧会话并 join 等其完全退场（旧任务 finally 会清理共享流/复位状态，
     * 与新会话重叠会误杀——join 线性化消除竞态），再起新会话。
     * join 上限 3s 兜底（读循环被关流 sentinel 即时唤醒，正常远快于此）。
     */
    suspend fun startSafe(transport: MirrorTransport, surface: Surface, opts: MirrorOptions) {
        stop()
        try { withTimeoutOrNull(3000) { job?.join() } } catch (_: Exception) {}
        // 新会话：复位控制通道健康度并换用全新写线程。旧线程可能仍卡在无超时的写里，
        // shutdownNow() 会中断其 wait()；即便中断无效，也只是泄漏一条守护线程。
        resetControlChannel()
        job = scope.launch { runLoop(transport, surface, opts) }
    }

    /** 复位控制通道健康度并换用全新写线程（新会话启动时调用）。 */
    private fun resetControlChannel() {
        controlHealthy = true
        controlWriteInFlight = false
        try { controlWriteExecutor.shutdownNow() } catch (_: Exception) {}
        controlWriteExecutor = newControlWriteExecutor()
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

    // ── 控制通道（触摸 / 按键的快速注入路径） ──

    /**
     * 控制通道是否可用（Streaming、通道健康且 control 流已建立）。
     * 不可用时调用方应回退到 `input` 命令路径，否则触摸会静默失效。
     */
    val isControlReady: Boolean get() = running && controlHealthy && controlStream?.isOpen == true

    /**
     * 向 control 通道写一条控制消息。
     * 返回 false 表示通道不可用（未就绪 / 超时 / 写失败 / 已被判定失效），
     * 调用方**必须**回退到 `input` 命令路径；此后 [isControlReady] 同步转为 false。
     *
     * 【为什么必须专用线程 + 有界等待】
     * 无线侧的 `AdbStream.write()`（libadb 3.1.1）是**无超时的同步阻塞**实现：
     * ```
     * synchronized (this) {
     *     while (!mIsClosed && !mWriteReady.compareAndSet(true, false)) wait();
     * }
     * ```
     * `mWriteReady` 只在连接线程收到设备的 **OKAY** 后置位；而 adbd 在对端读得慢、
     * 本地 socket 背压时会**扣住 OKAY 不发**。因此链路抖动/背压时该调用可能**永不返回**
     * ——历史版本直接在主线程调用，触发过 `AnrType=input.app`。
     *
     * 现在：写操作丢到专用单线程执行，这里最多等 [CONTROL_WRITE_TIMEOUT_MS]；超时即
     * 判定通道不可用并返回 false。在途写同时只允许一笔，避免拖动时在背压链路上无限排队。
     */
    fun sendControl(msg: ByteArray): Boolean {
        val c = controlStream ?: return false
        if (!running || !c.isOpen) return false
        if (!controlHealthy) return false
        if (controlWriteInFlight) {
            // 上一笔仍在写：**跳过本次，但不判死通道**。正常情况下注入已由 VM 的
            // 单车道（limitedParallelism(1)）串行化，这里只是防御性保护；若据此把
            // 通道判死，高频 MOVE 会把健康通道误杀（滑动立刻失效）。
            Log.w(TAG, "control 上一笔写入仍在途 → 丢弃本次写入（通道保持健康）")
            return false
        }
        controlWriteInFlight = true
        return try {
            val ok = controlWriteExecutor
                .submit(Callable { c.writeBytes(msg) })
                .get(CONTROL_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!ok) {
                controlHealthy = false
                Log.w(TAG, "control 写入返回失败 → 判定控制通道不可用")
            }
            ok
        } catch (e: TimeoutException) {
            controlHealthy = false
            Log.w(TAG, "control 写入超时(${CONTROL_WRITE_TIMEOUT_MS}ms) → 判定控制通道不可用")
            false
        } catch (e: Exception) {
            controlHealthy = false
            Log.w(TAG, "control 写入异常: ${e.message} → 判定控制通道不可用")
            false
        } finally {
            controlWriteInFlight = false
        }
    }

    /** 注入一次完整触摸（DOWN + UP），用于单击等无拖拽场景。 */
    fun injectTap(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (!isControlReady) return false
        val down = sendControl(
            ScrcpyControlProtocol.injectTouch(ScrcpyControlProtocol.ACTION_DOWN, x, y, w, h)
        )
        if (!down) return false
        return sendControl(
            ScrcpyControlProtocol.injectTouch(ScrcpyControlProtocol.ACTION_UP, x, y, w, h)
        )
    }

    /** 注入一次按键（自动配对 DOWN/UP）。 */
    fun injectKey(keycode: Int): Boolean {
        if (!isControlReady) return false
        val down = sendControl(
            ScrcpyControlProtocol.injectKeycode(ScrcpyControlProtocol.ACTION_DOWN, keycode)
        )
        if (!down) return false
        return sendControl(
            ScrcpyControlProtocol.injectKeycode(ScrcpyControlProtocol.ACTION_UP, keycode)
        )
    }

    /** 彻底销毁（repository destroy 时调用）。 */
    fun destroy() {
        stop()
        scope.cancel()
        // 释放控制写线程（可能仍卡在无超时的写里，shutdownNow 会中断其 wait()）
        try { controlWriteExecutor.shutdownNow() } catch (_: Exception) {}
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
            // 0. 会话 scid：随机生成 → 套接字名随之唯一，天然避免与上一个 server 撞名。
            //    因此不再需要 pkill 旧 server，也不再需要等 200ms（历史实现的两处固定开销）。
            val scid = newScid()
            val abstractDest = abstractDestFor(scid)

            // 1+2. 推送 server jar 与取设备真实分辨率互不依赖 → 并行，省掉一次往返的串行等待。
            //      pushServerIfNeeded 内部含 `ls -l` 与可能的 push（数百 ms），
            //      `wm size` 是一次轻量命令，并行后启动关键路径只取决于较慢的那个。
            //      用 *Serialized 变体：USB 侧直通（真并行），无线侧经闸门串行
            //      （libadb-android 非线程安全，并发会撞出重复 localId）。
            val (pushed, realSize) = coroutineScope {
                val pushDeferred = async { pushServerIfNeeded(transport) }
                val sizeDeferred = async { parseWmSize(transport.executeCommandSerialized("wm size")) }
                pushDeferred.await() to sizeDeferred.await()
            }
            if (!pushed) {
                _status.value = MirrorStatus.Error("推送 scrcpy-server.jar 失败")
                return
            }
            _realSize.value = realSize

            // 3. 启动 server。control=true：control socket 必须连接（server 才会开始发视频头，
            //    熄屏也走此通道）；clipboard_autosync=false 抑制控制流上的设备消息；
            //    声音恒不转发（audio=false）；send_*_meta=false 使视频流直接从 12B codec 头开始。
            val launchCmd = buildString {
                append("CLASSPATH=$SCRCPY_REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SCRCPY_VERSION")
                // scid 必须以十六进制传入（server 用 radix 16 解析），且与套接字名同数值。
                append(" scid=").append(scidArg(scid))
                append(" log_level=info video=true audio=false control=true tunnel_forward=true cleanup=false")
                append(" video_codec=h264 max_size=${opts.maxSize} video_bit_rate=${opts.bitRate} max_fps=${opts.maxFps}")
                append(" send_device_meta=false send_dummy_byte=false send_frame_meta=true send_codec_meta=true")
                append(" clipboard_autosync=false")
                if (opts.stayAwake) append(" stay_awake=true")
            }
            launchS = transport.openShellStream(launchCmd)
            launchStream = launchS
            startLaunchLogDrain(launchS)

            // 4. 连接 video 抽象套接字（server 启动需数百 ms，短超时+重试）
            //    注意：这里必须用 openAbstractSocketWithRetry（while + break），
            //    不能用 `repeat(n) { … return@repeat }`——Kotlin 里 return@repeat 是
            //    continue 而非 break，循环会跑满 n 次（本文件历史版本即因此每次启动
            //    多开 19 条连接、并把 videoStream 覆盖成最后一条死连接）。
            val video = openAbstractSocketWithRetry(
                transport,
                abstractDest,
                label = "video",
                attempts = SOCKET_CONNECT_ATTEMPTS,
                timeoutMs = SOCKET_OPEN_TIMEOUT_MS,
                retryDelayMs = SOCKET_RETRY_DELAY_MS
            )
            if (video == null) {
                if (!running) return
                _status.value = MirrorStatus.Error("连接 scrcpy-server 失败（server 未就绪或不支持）")
                return
            }
            videoS = video
            videoStream = video

            // 5. 连接 control 套接字（server accept 顺序 video → control，必须成功）
            val control = openAbstractSocketWithRetry(
                transport,
                abstractDest,
                label = "control",
                attempts = SOCKET_CONNECT_ATTEMPTS,
                timeoutMs = SOCKET_OPEN_TIMEOUT_MS,
                retryDelayMs = SOCKET_RETRY_DELAY_MS
            )
            if (control == null) {
                if (!running) return
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
            //    状态暂不上 Streaming：等到第一个非 CONFIG 包真的送进解码器后再置位，
            //    避免"命令都通了但画面还没出来"的窗口期里 UI 已显示在投屏、
            //    用户点上去却毫无反应。
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
                            // 首帧真的进入解码器 → 画面即将出现，此时才置 Streaming。
                            // 用一次性判断避免每帧都做状态比较。
                            if (_status.value != MirrorStatus.Streaming) {
                                _status.value = MirrorStatus.Streaming
                                Log.i(TAG, "first video packet queued → Streaming (pts=$pts)")
                            }
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
            // 区分退场原因：stop()（用户/仓储主动停止）会先把 running 置 false；
            // 若走到 finally 时 running 仍为 true，说明读循环是因为流被链路关闭
            // 而 break（读取线程 EOF / 设备拔出），必须显式报错而非静默归 Idle
            // （2026-09-11 卡死假活修复的收尾半环：错误可见 + 可重试）。
            val abnormalExit = running
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
                    _status.value = if (abnormalExit) {
                        MirrorStatus.Error("连接已断开（链路失效或设备拔出）")
                    } else {
                        MirrorStatus.Idle
                    }
                }
            }
        }
    }

    /**
     * 轮询连接抽象套接字直到就绪，就绪即**立即返回**（真 break 语义）。
     *
     * 失败路径放弃的流一律 close：既避免 ADB 流泄漏，也避免多余连接被 server
     * 按序 accept 后占掉 video/control 的坑位（见类头注释的 accept 顺序约定）。
     * 调用方按返回值判空即可，不需要自己写循环。
     */
    private suspend fun openAbstractSocketWithRetry(
        transport: MirrorTransport,
        dest: String,
        label: String,
        attempts: Int,
        timeoutMs: Long,
        retryDelayMs: Long
    ): MirrorStream? {
        for (attempt in 1..attempts) {
            if (!running) return null
            val s = try {
                transport.openAbstractSocket(dest, timeoutMs)
            } catch (_: Exception) {
                null
            }
            if (s != null && s.isOpen) {
                Log.d(TAG, "socket ready[$label]: attempt=$attempt")
                return s
            }
            try { s?.close() } catch (_: Exception) {}
            if (attempt < attempts) delay(retryDelayMs)
        }
        Log.w(TAG, "socket NOT ready[$label]: ${attempts} attempts exhausted")
        return null
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
            val ls = transport.executeCommandSerialized("ls -l $SCRCPY_REMOTE_PATH 2>/dev/null")
            if (ls.contains(SCRCPY_REMOTE_PATH)) {
                // 已存在：补一次轻量校验，避免上次 push 被截断却留下同名文件
                val size = Regex("""(\d+)\s+.*scrcpy-server\.jar""").find(ls)
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (size == null || size <= 0L) {
                    Log.w(TAG, "远程 jar 存在但大小异常(size=$size)，重新推送")
                } else {
                    return true
                }
            }
            val tmp = File(appContext.cacheDir, "scrcpy-server.jar")
            appContext.assets.open(SCRCPY_ASSET_PATH).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val ok = transport.pushFileToSerialized(tmp, SCRCPY_REMOTE_PATH)
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
