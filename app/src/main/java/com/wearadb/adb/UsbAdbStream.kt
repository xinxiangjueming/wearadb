package com.wearadb.adb

import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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

        /**
         * 写入流控等待上限（等设备回 OKAY）。
         *
         * 必须是**有限且偏短**的值：同一条 USB 总线上的所有流共用唯一的读线程，
         * 而读线程除投递数据外还负责给收到的 WRTE 回 OKAY。若某次写长时间等不到
         * OKAY（拖动投屏时 adbd 正忙于处理 input 事件即会出现），等待方会一直占着
         * 写锁，后续写依次排队 → 读线程被阻塞 → 视频流读不到数据 → 表现为
         * "一点屏幕就断开"。1500ms 在真机往返（通常 <50ms）之上留足余量，
         * 又短到不会把级联堵死。
         */
        private const val WRITE_FLOW_CONTROL_TIMEOUT_MS = 1500L
    }

    private var remoteId: Int = 0
    private val readQueue = LinkedBlockingQueue<ByteArray>()
    private val openLatch = java.util.concurrent.CountDownLatch(1)
    private val writeReady = AtomicBoolean(false)
    private val writeLock: Any = Object()

    @Volatile var isClosed = false
        private set
    @Volatile var isOpened = false
        private set

    val isOpen: Boolean get() = isOpened && !isClosed

    /**
     * 等待流打开（收到 OKAY）。返回 false 表示超时**或**流已被对端关闭。
     *
     * 注意：adbd 对不存在的目标（如尚未监听的 localabstract:scrcpy）回的是 CLSE
     * 而不是 OKAY。若 onClosed() 不唤醒本闩锁，调用方只能等满 timeoutMs —— 这正是
     * 投屏探测"每次失败固定烧 1.5s"的来源。onClosed()/close() 现在都会 countDown，
     * 失败路径立即返回、由调用方按短超时重试。
     */
    fun waitForOpen(timeoutMs: Long): Boolean {
        val opened = openLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        // 被关闭唤醒时闩锁同样归零，必须用 isOpened 区分"真的打开了"与"提前退场"
        return opened && isOpened
    }

    /**
     * Called by the connection when OKAY is received for this stream's OPEN.
     */
    fun onOpened(remoteId: Int) {
        this.remoteId = remoteId
        this.isOpened = true
        writeReady.set(true)
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
        writeReady.set(true)
        Log.d(TAG, "Stream $localId onOkay: writeReady=true")
        synchronized(writeLock) {
            (writeLock as java.lang.Object).notifyAll()
        }
    }

    /**
     * Wait for write to be ready (OKAY received for previous write).
     */
    fun waitForWriteReady(timeoutMs: Long = WRITE_FLOW_CONTROL_TIMEOUT_MS): Boolean {
        val endTime = System.currentTimeMillis() + timeoutMs
        synchronized(writeLock) {
            while (!writeReady.get() && !isClosed) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    Log.w(TAG, "Stream $localId waitForWriteReady: TIMEOUT after ${timeoutMs}ms")
                    return false
                }
                (writeLock as java.lang.Object).wait(remaining)
            }
        }
        val result = writeReady.get()
        Log.d(TAG, "Stream $localId waitForWriteReady: result=$result")
        return result
    }

    /**
     * Mark write as not ready (called before sending data).
     */
    fun markWriteNotReady() {
        writeReady.set(false)
    }

    /**
     * Called by the connection when CLSE is received.
     * Sets isClosed and wakes all blocked readers (same as AdbStream.notifyClose)
     * **以及等待 OPEN 的 waitForOpen**——设备拒绝 OPEN 时（目标不存在）回的是
     * CLSE 而非 OKAY，不唤醒的话调用方要白等满整个 timeout。
     */
    fun onClosed() {
        isClosed = true
        openLatch.countDown()
        readQueue.offer(ByteArray(0)) // Sentinel to unblock readers
        Log.d(TAG, "Stream $localId closed by remote")
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            openLatch.countDown()
            readQueue.offer(ByteArray(0)) // Sentinel
        }
    }

    fun getRemoteId(): Int = remoteId

    /**
     * Read one chunk, blocking until data arrives or stream closes.
     */
    fun readBlocking(timeoutMs: Long = 5000): ByteArray? {
        val data = readQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (data == null) return null
        if (data.isEmpty()) return null // sentinel
        return data
    }

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

    /**
     * Read all available data as raw bytes (blocking until stream closes or timeout).
     * Unlike readAll(), preserves binary data integrity (no String conversion).
     */
    fun readAllBytes(timeoutMs: Long = 30000): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val endTime = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                val data = readQueue.poll() ?: break
                if (data.isEmpty()) return buffer.toByteArray() // sentinel = stream closed
                buffer.write(data)
            }
            while (System.currentTimeMillis() < endTime) {
                val data = readQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                when {
                    data == null -> {
                        if (isClosed) break
                    }
                    data.isEmpty() -> break
                    else -> buffer.write(data)
                }
            }
        } catch (_: InterruptedException) {}
        return buffer.toByteArray()
    }

    fun createWriteMessage(data: ByteArray): UsbAdbProtocol.AdbMessage {
        return UsbAdbProtocol.writeMessage(localId, remoteId, data)
    }

    fun createCloseMessage(): UsbAdbProtocol.AdbMessage {
        return UsbAdbProtocol.closeMessage(localId, remoteId)
    }

    /**
     * Write data to this stream via the parent connection.
     * Waits for OKAY from device before sending (ADB flow control).
     *
     * **串行化**：整个「等 OKAY → 标记未就绪 → 发包」序列必须原子完成。
     * 此前无锁版本在投屏拖动（每帧一条 control 消息）下会有数十条写并发挤入，
     * 全部排队等 OKAY，把共用读线程堵死，直接导致 USB 会话表现为断开。
     * 这里用 writeLock 串行化，配合 [WRITE_FLOW_CONTROL_TIMEOUT_MS] 的短超时，
     * 使最坏等待时间有上界。
     *
     * 超时（返回 false）时**仍然发送**：宁可放弃一次流控保真，也不能让
     * 一条卡的写把整条总线拖死——这是"可用性优先于严格流控"的取舍。
     */
    fun write(data: ByteArray, connection: UsbAdbConnection) {
        synchronized(writeLock) {
            val ready = waitForWriteReady()
            if (!ready && !isClosed) {
                Log.w(TAG, "Stream $localId write: 流控超时，仍继续发送（避免堵死总线）")
            }
            writeReady.set(false)
            connection.sendMessage(createWriteMessage(data))
        }
    }
}
