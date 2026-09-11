package com.wearadb.adb

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * scrcpy H.264 裸流解码器（B1 有线投屏核心件）。
 *
 * 数据来源：scrcpy-server 经 `localabstract:scrcpy` 回传的 2.x 线协议裸包
 * （已由 UsbAdbRepository 的读循环按 12 字节 meta 头拆包）。
 *
 * 线程模型：单一调用线程（投屏读循环协程）串行调用 configure()/feed()/release()，
 * 内部用 @Synchronized 兜底，与 stopScreenMirror 的跨线程释放互斥。
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
    }

    private var codec: MediaCodec? = null
    private var configured = false

    /** 已成功 configure（收到首个 CONFIG 包并完成 csd-0 配置）。 */
    val isConfigured: Boolean get() = configured

    /** 当前解码分辨率（来自 scrcpy 视频头，即编码尺寸）。 */
    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set

    /**
     * 用首个 CONFIG 包（SPS+PPS，Annex-B）+ 分辨率配置解码器并绑定 Surface。
     * 若已配置过（如分辨率变化触发的重配置），先释放旧实例。
     */
    @Synchronized
    fun configure(surface: Surface, width: Int, height: Int, csd0: ByteArray): Boolean {
        release()
        return try {
            val format = MediaFormat.createVideoFormat(MIME_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            val c = MediaCodec.createDecoderByType(MIME_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            videoWidth = width
            videoHeight = height
            configured = true
            Log.i(TAG, "configure OK: ${width}x${height}, csd0=${csd0.size}B")
            true
        } catch (e: Exception) {
            Log.e(TAG, "configure FAILED: ${e.message}", e)
            release()
            false
        }
    }

    /**
     * 喂一个裸包。CONFIG 包不入队（其内容已作为 csd-0 参与 configure）。
     * 解码输出直接 releaseOutputBuffer(render=true) 渲染到 Surface。
     */
    @Synchronized
    fun feed(payload: ByteArray, isConfig: Boolean, ptsUs: Long) {
        if (isConfig) return
        val c = codec ?: return
        if (!configured) return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)
                if (buf != null) {
                    buf.clear()
                    val n = minOf(payload.size, buf.capacity())
                    if (n < payload.size) {
                        Log.w(TAG, "input buffer too small: need=${payload.size} cap=${buf.capacity()}")
                    }
                    buf.put(payload, 0, n)
                    c.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                } else {
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                }
            }
            drain(c)
        } catch (e: Exception) {
            Log.w(TAG, "feed failed: ${e.message}")
        }
    }

    /** 非阻塞排空输出缓冲，全部渲染到 Surface。 */
    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = try { c.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            if (outIdx < 0) break // TRY_AGAIN_LATER(-1) / FORMAT_CHANGED(-2) / EOS(-4)
            try { c.releaseOutputBuffer(outIdx, true) } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        configured = false
    }
}
