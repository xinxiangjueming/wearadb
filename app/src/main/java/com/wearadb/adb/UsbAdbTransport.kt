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
        private const val WRITE_TIMEOUT = 1000
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
     * Uses UsbRequest pool + requestWait() (no timeout, blocks until data).
     * Returns actual bytes read, or throws IOException on failure.
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
                request.queue(buf, needed)

                // requestWait() without timeout — blocks until data arrives
                val response = conn.requestWait()
                if (response == null || response !== request) {
                    if (++retryCount > maxRetries) {
                        throw IOException("USB read failed after $maxRetries retries")
                    }
                    continue
                }

                // UsbRequest updates buf.position() with actual bytes read after requestWait().
                val bytesRead = buf.position()
                if (bytesRead > 0) {
                    buf.rewind()
                    buf.get(buffer, offset + totalRead, bytesRead)
                    totalRead += bytesRead
                    retryCount = 0
                    Log.v(TAG, "read: +$bytesRead total=$totalRead/$length")
                } else {
                    // 0 bytes — possibly timeout or empty response
                    if (++retryCount > maxRetries) {
                        throw IOException("USB read: no data after $maxRetries retries")
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
     * Uses bulkTransfer with the full remaining size.
     */
    fun writeBuffer(buffer: ByteArray) {
        if (closed) throw IOException("Transport closed")
        var offset = 0

        while (offset < buffer.size) {
            val remaining = buffer.size - offset
            // bulkTransfer needs a contiguous array starting at index 0
            val chunk = if (offset == 0 && remaining == buffer.size) buffer
                        else buffer.copyOfRange(offset, offset + remaining)
            val transferred = conn.bulkTransfer(outEp, chunk, remaining, WRITE_TIMEOUT)
            if (transferred < 0) {
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
