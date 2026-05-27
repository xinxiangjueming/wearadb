package com.wearadb.adb

import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

/**
 * Represents a single ADB stream (e.g., a shell session or sync channel).
 * Data is received via the background reader thread and queued for consumption.
 *
 * Uses wait/notify pattern (same as cgutman/AdbLib AdbStream) for efficient blocking.
 */
class UsbAdbStream(
    val localId: Int,
    val destination: String
) {
    companion object {
        private const val TAG = "UsbAdbStream"
    }

    private var remoteId: Int = 0
    private val readQueue = LinkedBlockingQueue<ByteArray>()
    private val openLatch = java.util.concurrent.CountDownLatch(1)

    @Volatile var isClosed = false
        private set
    @Volatile var isOpened = false
        private set

    val isOpen: Boolean get() = isOpened && !isClosed

    /**
     * Wait for the stream to be opened (OKAY received).
     */
    fun waitForOpen(timeoutMs: Long): Boolean {
        return openLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Called by the connection when OKAY is received for this stream's OPEN.
     */
    fun onOpened(remoteId: Int) {
        this.remoteId = remoteId
        this.isOpened = true
        openLatch.countDown()
        Log.d(TAG, "Stream $localId opened (remote=$remoteId)")
    }

    /**
     * Called by the connection when WRTE data is received.
     */
    fun onData(data: ByteArray) {
        if (!isClosed) {
            readQueue.offer(data)
        }
    }

    /**
     * Called by the connection when OKAY is received (write acknowledgment).
     */
    fun onOkay() {
        // Write was acknowledged
    }

    /**
     * Called by the connection when CLSE is received.
     * Sets isClosed and wakes all blocked readers (same as AdbStream.notifyClose).
     */
    fun onClosed() {
        isClosed = true
        readQueue.offer(ByteArray(0)) // Sentinel to unblock readers
        Log.d(TAG, "Stream $localId closed by remote")
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            readQueue.offer(ByteArray(0)) // Sentinel
        }
    }

    fun getRemoteId(): Int = remoteId

    /**
     * Read one chunk of data from this stream.
     * Returns data bytes, or empty ByteArray if stream is closed.
     * Blocks until data arrives or stream closes.
     *
     * Based on cgutman/AdbLib AdbStream.read() pattern.
     */
    fun read(): ByteArray? {
        val data = readQueue.poll()
        if (data != null) {
            return if (data.isEmpty()) null else data  // sentinel → null (EOF)
        }
        if (isClosed) return null
        return ByteArray(0) // no data yet, not closed
    }

    /**
     * Read all available data as a string (blocking until stream closes or timeout).
     * Based on ADB-SafeScan's read loop: accumulate chunks until stream closes.
     */
    fun readAll(timeoutMs: Long = 30000): String {
        val sb = StringBuilder()
        val endTime = System.currentTimeMillis() + timeoutMs
        try {
            // 先消费队列中已有的数据（可能在 readAll 调用前就已经到达）
            while (true) {
                val data = readQueue.poll() ?: break
                if (data.isEmpty()) return sb.toString() // sentinel = stream closed
                sb.append(String(data))
            }
            // 再等待新数据到达
            while (System.currentTimeMillis() < endTime) {
                val data = readQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                when {
                    data == null -> {
                        if (isClosed) break
                    }
                    data.isEmpty() -> break
                    else -> sb.append(String(data))
                }
            }
        } catch (_: InterruptedException) {}
        return sb.toString()
    }

    fun createWriteMessage(data: ByteArray): UsbAdbProtocol.AdbMessage {
        return UsbAdbProtocol.writeMessage(localId, remoteId, data)
    }

    fun createCloseMessage(): UsbAdbProtocol.AdbMessage {
        return UsbAdbProtocol.closeMessage(localId, remoteId)
    }
}
