package com.wearadb.adb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.LinkedList

/**
 * USB transport for ADB protocol.
 * Based on cgutman/AdbLib UsbChannel approach (used by ADB-SafeScan).
 *
 * Key design:
 * - Writes use bulkTransfer (header + payload separately)
 * - Reads use UsbRequest + requestWait() with UsbRequest pool
 * - requestWait() called WITHOUT timeout (blocks until data arrives)
 */
class UsbAdbTransport(
    private val conn: UsbDeviceConnection,
    private val outEp: UsbEndpoint,
    private val inEp: UsbEndpoint
) {
    companion object {
        private const val TAG = "UsbAdbTransport"
        private const val WRITE_TIMEOUT = 5000

        /**
         * 单次 requestWait 的有界等待。
         * 纯空闲链路（无视频流、无命令）长时间无数据是**常态**，超时不算错误、
         * 对同一个 request 原地续等；收益是永不永久悬挂——链路劣化/设备拔出时
         * 最终必然走出等待进入错误路径（2026-09-11 卡死排查结论）。
         */
        private const val REQUEST_WAIT_TIMEOUT_MS = 30_000L
    }

    @Volatile
    private var closed = false

    // UsbRequest pool (reuse instead of create/destroy each time)
    private val requestPool = LinkedList<UsbRequest>()
    private val poolLock = Any()

    val inputStream: InputStream = UsbAdbInputStream()
    val outputStream: OutputStream = UsbAdbOutputStream()

    fun close() {
        closed = true
        synchronized(poolLock) {
            requestPool.forEach { try { it.close() } catch (_: Exception) {} }
            requestPool.clear()
        }
    }

    private fun getInRequest(): UsbRequest {
        synchronized(poolLock) {
            val existing = requestPool.pollFirst()
            if (existing != null) return existing
        }
        val req = UsbRequest()
        req.initialize(conn, inEp)
        return req
    }

    private fun releaseInRequest(req: UsbRequest) {
        synchronized(poolLock) {
            requestPool.addLast(req)
        }
    }

    // ── Read (UsbRequest + requestWait, following ADB-SafeScan) ──

    /**
     * Read exactly `length` bytes into `buffer`.
     * Uses UsbRequest pool + requestWait(timeout) (bounded wait, re-waits the
     * same queued request while idle). Returns actual bytes read, or throws
     * IOException on failure.
     *
     * 两处硬化（2026-09-11 卡死排查结论）：
     * 1. queue() 返回值必须检查——队列提交失败（句柄/端点已死）时若继续
     *    requestWait 会永久悬挂；
     * 2. requestWait 带 30s 超时——空闲时对同一 request 续等，链路死亡时
     *    最终必然抛 IOException（由读取线程统一走链路死亡处理）。
     */
    fun readExactly(buffer: ByteArray, offset: Int, length: Int) {
        if (closed) throw IOException("Transport closed")
        var totalRead = 0
        var retryCount = 0
        val maxRetries = 3

        while (totalRead < length) {
            val needed = length - totalRead
            val buf = ByteBuffer.allocate(needed)
            val request = getInRequest()
            try {
                @Suppress("DEPRECATION")
                if (!request.queue(buf, needed)) {
                    Log.e(TAG, "USB read queue FAILED (needed=$needed, totalRead=$totalRead/$length)")
                    throw IOException("USB read queue failed")
                }

                // 有界等待完成：null = 空闲超时，不算错误，对同一个 request 续等
                var response = conn.requestWait(REQUEST_WAIT_TIMEOUT_MS)
                while (response == null && !closed) {
                    response = conn.requestWait(REQUEST_WAIT_TIMEOUT_MS)
                }
                if (response == null) {
                    throw IOException("USB read aborted (transport closed while waiting)")
                }

                // UsbRequest updates buf.position() with actual bytes read after requestWait().
                val bytesRead = if (response === request) buf.position() else -1
                if (bytesRead > 0) {
                    buf.rewind()
                    buf.get(buffer, offset + totalRead, bytesRead)
                    totalRead += bytesRead
                    retryCount = 0
                    Log.v(TAG, "read: +$bytesRead total=$totalRead/$length")
                } else {
                    if (bytesRead < 0) {
                        Log.w(TAG, "USB read: unexpected response (mismatched request), retry=$retryCount")
                    } else {
                        // 0 bytes — possibly timeout or empty response
                        Log.w(TAG, "USB read: 0 bytes, retry=$retryCount")
                    }
                    if (++retryCount > maxRetries) {
                        Log.e(TAG, "USB read FAILED after $maxRetries retries, totalRead=$totalRead/$length")
                        throw IOException("USB read failed after $maxRetries retries")
                    }
                }
            } finally {
                releaseInRequest(request)
            }
        }
    }

    // ── Write (bulkTransfer, header + payload separately) ──

    /**
     * Write `buffer` to USB OUT endpoint.
     * Splits into chunks matching the endpoint's maxPacketSize to avoid
     * Android USB bulk transfer size limits.
     */
    fun writeBuffer(buffer: ByteArray) {
        if (closed) throw IOException("Transport closed")
        // Use endpoint maxPacketSize as chunk limit (typically 512 for USB 2.0)
        val maxChunk = outEp.maxPacketSize.coerceAtLeast(512)
        var offset = 0

        while (offset < buffer.size) {
            val remaining = buffer.size - offset
            val len = minOf(remaining, maxChunk)
            val chunk = if (offset == 0 && len == buffer.size) buffer
                        else buffer.copyOfRange(offset, offset + len)
            val transferred = conn.bulkTransfer(outEp, chunk, len, WRITE_TIMEOUT)
            if (transferred < 0) {
                Log.e(TAG, "USB bulk write FAILED: transferred=$transferred, offset=$offset, total=${buffer.size}, maxChunk=$maxChunk")
                throw IOException("USB bulk write failed (transferred=$transferred)")
            }
            offset += transferred
        }
    }

    /**
     * Write an ADB message as TWO separate bulk transfers:
     * 1. 24-byte header
     * 2. payload (if any)
     *
     * ADB-SafeScan note: "writing header+payload as a single buffer produces
     * different results from writing them separately"
     */
    fun writeMessage(header: ByteArray, payload: ByteArray?) {
        writeBuffer(header)
        if (payload != null && payload.isNotEmpty()) {
            writeBuffer(payload)
        }
    }

    // ── InputStream adapter (for UsbAdbConnection.readMessage) ──

    private inner class UsbAdbInputStream : InputStream() {
        private var readBuf = ByteArray(0)
        private var readPos = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n <= 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1

            // Use buffered data first
            if (readPos < readBuf.size) {
                val available = readBuf.size - readPos
                val toCopy = minOf(available, len)
                readBuf.copyInto(b, off, readPos, readPos + toCopy)
                readPos += toCopy
                return toCopy
            }

            // Read a new USB packet via UsbRequest
            try {
                readExactly(b, off, len)
                return len
            } catch (e: IOException) {
                Log.e(TAG, "InputStream read failed: ${e.message}")
                return 0
            }
        }

        override fun close() {
            closed = true
        }
    }

    // ── OutputStream adapter ──

    private inner class UsbAdbOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Transport closed")
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            writeBuffer(data)
        }

        override fun close() {
            closed = true
        }
    }
}
