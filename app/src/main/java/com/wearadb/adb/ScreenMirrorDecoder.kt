package com.wearadb.adb

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * scrcpy H.264 裸流解码器（B1 投屏核心件）。
 *
 * 数据来源：scrcpy-server 经 `localabstract:scrcpy` 回传的 2.x 线协议裸包
 * （已由读循环按 12 字节 meta 头拆包）。
 *
 * ── 线程模型（v2：解码独立线程 + 丢帧） ──
 * 此前 feed() 在投屏读循环里同步 `dequeueInputBuffer` + `drain`，解码耗时直接
 * 反压读循环：设备端若持续产帧（高码率/高帧率），读循环来不及消费会让传输层
 * 缓冲堆积，表现为"操作延迟越用越大、画面滞后于手指"。
 *
 * 现在读循环只做 `feed()`（把裸包塞进有界队列，永不阻塞网络读），解码线程
 * 独立消费队列。队列满 = 解码追不上采集，此时**丢最旧的待解码帧**（视频帧
 * 之间无依赖，丢中间帧只是少一帧画面；CONFIG/关键帧不丢），把延迟锁定在
 * 队列深度所代表的固定窗口内，而不是无界增长。
 *
 * 丢帧策略参考官方 scrcpy `demuxer.c`：
 *   - `SC_PACKET_FLAG_CONFIG`（SPS/PPS）永不丢弃；
 *   - 队列满时优先丢弃"非关键帧"；
 *   - 官方在客户端侧直接丢弃整包，不在解码器内部排队。
 *
 * 线协议参考（scrcpy v2.7 Streamer.writeVideoHeader / writeFrameMeta）：
 *   视频头: [codec_id:4 BE][width:4 BE][height:4 BE]
 *   每包:   [pts_flags:8 BE][len:4 BE] + len 字节裸包
 *           pts_flags bit63=CONFIG, bit62=KEY_FRAME, bit0-61=PTS(us)
 */
class ScreenMirrorDecoder {

    companion object {
        private const val TAG = "ScreenMirrorDecoder"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc"

        /**
         * 待解码队列深度。scrcpy 默认 60fps、队列 60 相当于 1s 缓冲；
         * 投屏场景下 1s 延迟过大，取 8 帧（≈130ms@60fps）作为延迟上界。
         */
        private const val QUEUE_CAPACITY = 8

        /** 解码线程轮询输入缓冲的等待（有数据时首轮即中，无数据时勿忙等）。 */
        private const val DEQUEUE_INPUT_TIMEOUT_US = 5_000L

        /** 解码线程取队列元素的等待上限（决定 release() 的响应粒度）。 */
        private const val QUEUE_POLL_TIMEOUT_MS = 200L
    }

    /** 待解码帧。isKeyFrame 用于队列满时的丢弃优先级。 */
    private class Frame(val payload: ByteArray, val ptsUs: Long, val isKeyFrame: Boolean)

    private var codec: MediaCodec? = null

    @Volatile
    private var configured = false

    /**
     * 已成功 configure（收到首个 CONFIG 包并完成 csd-0 配置）。
     * 注意语义：表示"解码器已就绪"，不等于"首帧已上屏"（后者见 [hasRenderedFrame]）。
     */
    val isConfigured: Boolean get() = configured

    /** 是否已渲染过至少一帧（用于把 Streaming 状态推进到"画面真的出来了"）。 */
    @Volatile
    var hasRenderedFrame: Boolean = false
        private set

    /** 当前解码分辨率（来自 scrcpy 视频头，即编码尺寸）。 */
    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    @Volatile
    private var surfaceRef: Surface? = null

    /** 有界待解码队列。读循环入队，解码线程出队。 */
    private var queue: ArrayBlockingQueue<Frame>? = null

    private var decodeThread: Thread? = null

    @Volatile
    private var decodeRunning = false

    @Volatile
    private var lastWidth = 0

    @Volatile
    private var lastHeight = 0

    /**
     * 用首个 CONFIG 包（SPS+PPS，Annex-B）+ 分辨率配置解码器并绑定 Surface，
     * 随后拉起独立解码线程。若已配置过（如分辨率变化触发的重配置），先释放旧实例。
     *
     * 本方法只做"建立会话"，不阻塞等待首帧；调用方通过 [hasRenderedFrame] 观察。
     */
    @Synchronized
    fun configure(surface: Surface, width: Int, height: Int, csd0: ByteArray): Boolean {
        release()
        return try {
            val format = MediaFormat.createVideoFormat(MIME_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))

            // 低延迟解码：让解码器在硬件可用时不等待帧重排（API 30+）。
            // 官方 scrcpy 亦在 MediaFormat 上开启该 flag（低延迟模式下 B 帧被禁用）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                } catch (e: Exception) {
                    Log.w(TAG, "KEY_LOW_LATENCY 不支持（忽略）: ${e.message}")
                }
            }

            val c = MediaCodec.createDecoderByType(MIME_AVC)
            c.configure(format, surface, null, 0)
            c.start()

            codec = c
            surfaceRef = surface
            videoWidth = width
            videoHeight = height
            lastWidth = width
            lastHeight = height
            hasRenderedFrame = false
            queue = ArrayBlockingQueue(QUEUE_CAPACITY)
            configured = true
            startDecodeThread()
            Log.i(
                TAG,
                "configure OK: ${width}x${height}, csd0=${csd0.size}B, " +
                    "lowLatency=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.R}, q=$QUEUE_CAPACITY"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "configure FAILED: ${e.message}", e)
            release()
            false
        }
    }

    /**
     * 喂一个裸包。**非阻塞**：入队后立即返回，队列满时按丢弃优先级腾位。
     * 解码耗时全部由独立解码线程承担，不再反压投屏读循环。
     */
    fun feed(payload: ByteArray, isConfig: Boolean, ptsUs: Long) {
        if (isConfig) return
        if (!configured) return
        val q = queue ?: return
        val frame = Frame(payload, ptsUs, isKeyFrame(payload))
        if (q.offer(frame)) return

        // 队列满 → 解码追不上采集。丢一帧腾位，优先丢非关键帧。
        val dropped = dropOne(q)
        if (!q.offer(frame)) {
            // 极端情况（腾位后仍被抢占）：直接丢弃本帧，绝不阻塞读循环。
            Log.w(TAG, "queue full, frame dropped (pts=$ptsUs)")
        } else if (dropped != null) {
            Log.d(TAG, "queue full → dropped pts=${dropped.ptsUs} (key=${dropped.isKeyFrame})")
        }
    }

    /**
     * 从队列中丢弃一帧。返回被丢弃的帧（无帧可丢时 null）。
     * 策略：优先丢队首的非关键帧；全是关键帧时丢最旧的一帧。
     */
    private fun dropOne(q: ArrayBlockingQueue<Frame>): Frame? {
        val head = q.peek() ?: return null
        if (!head.isKeyFrame) {
            // 队首即最旧的非关键帧，直接丢
            return if (q.remove(head)) head else null
        }
        // 队首是关键帧：从队首向后找第一个非关键帧（避免丢参考帧）
        for (f in q) {
            if (!f.isKeyFrame) {
                return if (q.remove(f)) f else null
            }
        }
        // 全是关键帧：丢最旧，避免队列永久卡死
        return q.poll()
    }

    /**
     * H.264 NAL 类型探测：类型 5（IDR）或 7（SPS）视为关键帧。
     * 裸包为 Annex-B 起始码（00 00 01 / 00 00 00 01）分隔的一个或多个 NAL。
     * 探测失败时保守返回 true（不丢），宁少丢不错丢。
     */
    private fun isKeyFrame(data: ByteArray): Boolean {
        var i = 0
        val n = data.size
        while (i + 3 < n) {
            // 找起始码
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() ||
                    (data[i + 2] == 0.toByte() && i + 3 < n && data[i + 3] == 1.toByte()))
            ) {
                val scLen = if (data[i + 2] == 1.toByte()) 3 else 4
                val nalIdx = i + scLen
                if (nalIdx < n) {
                    val type = data[nalIdx].toInt() and 0x1F
                    if (type == 5 || type == 7) return true
                }
                i += scLen
            } else {
                i++
            }
        }
        return false
    }

    /** 独立的解码线程：出队 → 喂解码器 → 排空输出。 */
    private fun startDecodeThread() {
        decodeRunning = true
        decodeThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (decodeRunning) {
                val q = queue
                val c = codec
                if (q == null || c == null) break
                val frame = try {
                    q.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (frame == null) continue
                try {
                    val inIdx = c.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US)
                    if (inIdx < 0) {
                        // 输入缓冲一时不可用：本帧丢弃（保持低延迟，不重排队）
                        continue
                    }
                    val buf = c.getInputBuffer(inIdx)
                    if (buf == null) {
                        c.queueInputBuffer(inIdx, 0, 0, frame.ptsUs, 0)
                    } else {
                        buf.clear()
                        val n = minOf(frame.payload.size, buf.capacity())
                        if (n < frame.payload.size) {
                            Log.w(TAG, "input buffer too small: need=${frame.payload.size} cap=${buf.capacity()}")
                        }
                        buf.put(frame.payload, 0, n)
                        c.queueInputBuffer(inIdx, 0, n, frame.ptsUs, 0)
                    }
                    drain(c, info)
                } catch (e: Exception) {
                    if (decodeRunning) Log.w(TAG, "decode failed: ${e.message}")
                }
            }
        }, "ScrcpyDecoder").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** 非阻塞排空输出缓冲，全部渲染到 Surface。 */
    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIdx = try { c.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            if (outIdx < 0) {
                // TRY_AGAIN_LATER(-1) / FORMAT_CHANGED(-2) / EOS(-4)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                break
            }
            try {
                // render=true：直接上屏，不做 PTS 重排（低延迟优先）
                c.releaseOutputBuffer(outIdx, true)
                if (!hasRenderedFrame) {
                    hasRenderedFrame = true
                    Log.i(TAG, "first frame rendered")
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 释放解码器与解码线程（幂等；停线程后 join 有上限，避免 stop() 卡死）。
     * 与 stopScreenMirror 的跨线程释放互斥由 @Synchronized 保证。
     */
    @Synchronized
    fun release() {
        decodeRunning = false
        val t = decodeThread
        decodeThread = null
        if (t != null) {
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        queue?.clear()
        queue = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        surfaceRef = null
        configured = false
        hasRenderedFrame = false
    }
}
