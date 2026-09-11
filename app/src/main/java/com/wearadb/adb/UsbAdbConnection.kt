package com.wearadb.adb

import android.util.Log
import com.wearadb.adb.UsbAdbProtocol.AdbMessage
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB protocol connection over USB transport.
 * Implements CNXN/AUTH handshake and stream multiplexing (OPEN/OKAY/WRTE/CLSE).
 */
class UsbAdbConnection(
    private val transport: UsbAdbTransport,
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
    private val deviceName: String = "wear-adb",
    private val logCallback: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UsbAdbConnection"
        private const val MAX_PAYLOAD = 1048576  // 1MB max payload (API 28+)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback?.invoke(msg)
    }

    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, UsbAdbStream>()
    private val pendingOpens = ConcurrentHashMap<Int, UsbAdbStream>()

    /**
     * 总线写锁：一条 ADB 消息的 header+payload 两次 bulkTransfer 必须成对原子写出，
     * 否则多条流的写入交错会让设备侧字节流错位。所有写入（含 ACK）共用此锁。
     */
    private val busWriteLock = Any()

    @Volatile
    private var connected = false
    @Volatile
    private var closed = false

    val isConnected: Boolean get() = connected && !closed

    /**
     * Perform ADB connection handshake: CNXN → AUTH → CNXN(accept).
     * Must be called before opening streams.
     */
    fun connect(): Boolean {
        try {
            // 1. Send CNXN
            log("[1] 构造CNXN消息...")
            val cnxn = UsbAdbProtocol.cnxnMessage(MAX_PAYLOAD)
            val cnxnBytes = cnxn.toByteArray()
            log("[2] CNXN消息: ${cnxnBytes.size} bytes = ${cnxnBytes.joinToString("") { "%02x".format(it) }}")
            try {
                sendMessage(cnxn)
                log("[3] CNXN发送成功")
            } catch (e: Exception) {
                log("[3] CNXN发送失败: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }

            // 2. Read response (expect CNXN or AUTH)
            log("[4] 等待200ms...")
            Thread.sleep(200)
            log("[5] 开始读取响应 (最多15秒)...")
            val response = readMessage()
            if (response == null) {
                log("[5] 读取超时, 设备无响应")
                return false
            }
            log("[6] 收到: ${UsbAdbProtocol.commandName(response.command)} arg0=${response.arg0} arg1=${response.arg1} dataLen=${response.data.size}")

            when (response.command) {
                UsbAdbProtocol.CMD_CNXN -> {
                    connected = true
                    log("已连接 (无需认证)")
                    startReaderThread()
                    return true
                }
                UsbAdbProtocol.CMD_AUTH -> {
                    return handleAuth(response)
                }
                else -> {
                    log("CNXN收到意外响应: ${UsbAdbProtocol.commandName(response.command)}")
                    return false
                }
            }
        } catch (e: Exception) {
            log("连接异常: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    private fun handleAuth(authMsg: AdbMessage): Boolean {
        when (authMsg.arg0) {
            UsbAdbProtocol.AUTH_TYPE_TOKEN -> {
                val token = authMsg.data
                log("AUTH TOKEN (${token.size} bytes), 签名中...")

                // adbd 用 SHA-1 (RSA_sign/NID_sha1) 校验签名，必须用 SHA1withRSA；
                // 用 SHA256 会被永远拒绝，即使设备已保存本公钥也会重新弹授权
                val signature = Signature.getInstance("SHA1withRSA")
                signature.initSign(privateKey)
                signature.update(token)
                val signedToken = signature.sign()

                sendMessage(UsbAdbProtocol.authToken(signedToken))
                log(">>> AUTH SIGNATURE (${signedToken.size} bytes)")

                Thread.sleep(100)
                val response = readMessage()
                if (response == null) {
                    log("AUTH签名后无响应")
                    return false
                }
                log("<<< ${UsbAdbProtocol.commandName(response.command)}")

                return when (response.command) {
                    UsbAdbProtocol.CMD_CNXN -> {
                        connected = true
                        log("已连接 (签名认证)")
                        startReaderThread()
                        true
                    }
                    UsbAdbProtocol.CMD_AUTH -> {
                        log("签名被拒绝, 发送公钥...")
                        sendPublicKey()
                    }
                    else -> {
                        log("AUTH后收到意外响应: ${UsbAdbProtocol.commandName(response.command)}")
                        false
                    }
                }
            }
            else -> {
                log("未知AUTH类型 ${authMsg.arg0}, 发送公钥...")
                return sendPublicKey()
            }
        }
    }

    private fun sendPublicKey(): Boolean {
        // adbd 期望的格式是 base64(Android RSAPublicKey 结构体) + " " + 设备名 + "\0"，
        // 不能直接发 X.509 SPKI DER (publicKey.encoded)，否则设备存不下可用密钥，
        // 导致每次连接都重新弹授权
        val rsaPub = certificate.publicKey as java.security.interfaces.RSAPublicKey
        val pubKeyBytes = AndroidPubkeyCodec.encodeWithName(rsaPub, deviceName)
        sendMessage(UsbAdbProtocol.authPublicKey(pubKeyBytes))
        log(">>> AUTH PUBLIC_KEY (${pubKeyBytes.size} bytes)")

        Thread.sleep(100)
        val response = readMessage()
        if (response == null) {
            log("公钥发送后无响应")
            return false
        }
        log("<<< ${UsbAdbProtocol.commandName(response.command)}")

        return when (response.command) {
            UsbAdbProtocol.CMD_CNXN -> {
                connected = true
                log("已连接 (公钥认证)")
                startReaderThread()
                true
            }
            else -> {
                log("公钥后收到意外响应: ${UsbAdbProtocol.commandName(response.command)}")
                false
            }
        }
    }

    /**
     * Open a stream to a destination (e.g., "shell:ls", "shell:", "sync:").
     * Returns a UsbAdbStream for reading/writing.
     *
     * openTimeoutMs: 等待设备 OKAY 的超时。对"目标套接字可能尚不存在"的场景
     * （如 localabstract:scrcpy 对接尚未就绪的 scrcpy-server），调用方应传短超时
     * 并配合重试，避免每次失败都阻塞满 10s——设备拒绝时回 CLSE，
     * UsbAdbStream.onClosed() 会提前唤醒等待（见该类 waitForOpen 注释）。
     *
     * 未成功打开的流会从注册表摘除并 close，避免失败重试累积出
     * "设备侧不可见的幽灵流"（曾导致投屏把 videoStream 指向死连接）。
     */
    fun openStream(destination: String, openTimeoutMs: Long = 10000): UsbAdbStream {
        val localId = nextLocalId.getAndIncrement()
        val stream = UsbAdbStream(localId, destination)
        pendingOpens[localId] = stream
        streams[localId] = stream
        Log.d(TAG, "openStream: localId=$localId dest=$destination, totalStreams=${streams.size}")

        sendMessage(UsbAdbProtocol.openMessage(localId, destination))
        log(">>> OPEN id=$localId dest=$destination")

        // Wait for OKAY response (with remoteId)
        val opened = stream.waitForOpen(openTimeoutMs)
        Log.d(TAG, "openStream: localId=$localId opened=$opened")

        if (!opened) {
            // 打不开的流不留在注册表里：否则后续 OKAY/WRTE 会被投递给一条死流，
            // 且对端若已 CLSE 我们也无从回收。统一在这里摘除 + 关闭。
            pendingOpens.remove(localId)
            streams.remove(localId)
            stream.close()
        }

        return stream
    }

    /**
     * Open a stream for shell command execution.
     */
    fun openShell(command: String = ""): UsbAdbStream {
        val dest = if (command.isEmpty()) "shell:" else "shell:$command"
        return openStream(dest)
    }

    /**
     * Open a stream for file sync operations.
     */
    fun openSync(): UsbAdbStream {
        return openStream("sync:")
    }

    fun closeStream(stream: UsbAdbStream) {
        Log.d(TAG, "closeStream: localId=${stream.localId}, remaining=${streams.size - 1}")
        try {
            sendMessage(stream.createCloseMessage())
            streams.remove(stream.localId)
        } catch (e: Exception) {
            Log.w(TAG, "closeStream exception: ${e.message}")
        }
        stream.close()
    }

    fun disconnect() {
        Log.d(TAG, "disconnect: streams=${streams.size}, connected=$connected")
        closed = true
        connected = false
        streams.values.forEach { it.close() }
        streams.clear()
        pendingOpens.clear()
        transport.close()
        Log.d(TAG, "disconnect: done")
    }

    // ── Message I/O ──

    /**
     * Send a message on behalf of a stream, going through that stream's write lock
     * (ADB flow control: one outstanding WRTE per stream).
     */
    internal fun sendStreamMessage(stream: UsbAdbStream, msg: AdbMessage) {
        stream.write(msg.data, this)
    }

    /**
     * Send the reader thread's OKAY acknowledgement.
     *
     * **必须绕开流的 writeLock**：读线程在投递 WRTE 数据后要立即回 OKAY，
     * 若这条 ACK 去抢某条正在等流控（可能已排队数十条）的业务流写锁，
     * 读线程本身就会被阻塞 —— 而所有流的数据投递都依赖这一个读线程，
     * 于是形成「写等 OKAY、OKAY 等读线程、读线程等写锁」的死循环。
     * 协议上 ACK 是设备侧流控的唯一释放手段，因此它必须是永不阻塞的直发路径。
     */
    internal fun sendAck(msg: AdbMessage) {
        sendMessage(msg)
    }

    /**
     * Send ADB message as TWO separate bulk transfers (header + payload).
     * ADB-SafeScan: "writing header+payload as a single buffer produces
     * different results from writing them separately"
     *
     * **总线级串行化**：一条 ADB 消息 = header + payload 两次 bulkTransfer，
     * 中途插入另一条消息的 header 会让设备侧把字节流解析错位（表现为随机的
     * 协议错误 / 会话中断）。所有写入（业务包与 ACK）都从这里走同一把锁，
     * 保证每次「header+payload」成对原子落总线。
     */
    internal fun sendMessage(msg: AdbMessage) {
        val header = msg.toHeaderBytes()
        val payload = if (msg.data.isNotEmpty()) msg.data else null
        synchronized(busWriteLock) {
            transport.writeMessage(header, payload)
        }
    }

    private fun readMessage(): AdbMessage? {
        // Step 1: Read the 24-byte header (UsbRequest, blocks until data)
        log("  readMessage: 读取header...")
        val header = ByteArray(UsbAdbProtocol.HEADER_SIZE)
        try {
            transport.readExactly(header, 0, UsbAdbProtocol.HEADER_SIZE)
        } catch (e: Exception) {
            log("  readMessage: header读取失败: ${e.message}")
            return null
        }

        val command = UsbAdbProtocol.getIntLE(header, 0)
        val arg0 = UsbAdbProtocol.getIntLE(header, 4)
        val arg1 = UsbAdbProtocol.getIntLE(header, 8)
        val dataLen = UsbAdbProtocol.getIntLE(header, 12)

        log("  header: ${UsbAdbProtocol.commandName(command)} arg0=$arg0 arg1=$arg1 dataLen=$dataLen")

        // Step 2: Read the payload (if any)
        val data = if (dataLen > 0) {
            if (dataLen > MAX_PAYLOAD) {
                log("  payload过大: $dataLen")
                return null
            }
            val payload = ByteArray(dataLen)
            try {
                transport.readExactly(payload, 0, dataLen)
            } catch (e: Exception) {
                log("  payload读取失败: ${e.message}")
                return null
            }
            payload
        } else {
            ByteArray(0)
        }

        return AdbMessage(command, arg0, arg1, data)
    }

    // ── Background reader thread ──

    private fun startReaderThread() {
        Thread({
            log("读取线程启动")
            while (connected && !closed) {
                try {
                    val msg = readMessage()
                    if (msg == null) {
                        Log.w(TAG, "读取线程: EOF, connected=$connected closed=$closed")
                        connected = false
                        break
                    }
                    handleMessage(msg)
                } catch (e: Exception) {
                    if (!closed) {
                        Log.e(TAG, "读取线程错误: ${e.javaClass.simpleName}: ${e.message}", e)
                        connected = false
                    }
                    break
                }
            }
            Log.d(TAG, "读取线程退出, connected=$connected closed=$closed")
        }, "UsbAdbReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleMessage(msg: AdbMessage) {
        when (msg.command) {
            UsbAdbProtocol.CMD_OKAY -> {
                // In ADB protocol, responses from device have:
                //   arg0 = device's localId (= remoteId from host's perspective)
                //   arg1 = host's localId (= localId from host's perspective)
                val localId = msg.arg1   // host's localId
                val remoteId = msg.arg0  // device's localId
                Log.d(TAG, "<<< OKAY localId=$localId remoteId=$remoteId")

                val pending = pendingOpens.remove(localId)
                if (pending != null) {
                    Log.d(TAG, "<<< OKAY for OPEN stream $localId")
                    pending.onOpened(remoteId)
                    return
                }

                // Otherwise it's an ACK for a WRTE
                val stream = streams[localId]
                if (stream != null) {
                    Log.d(TAG, "<<< OKAY for WRTE stream $localId")
                    stream.onOkay()
                } else {
                    Log.w(TAG, "<<< OKAY for unknown stream $localId, streams=${streams.keys}")
                }
            }
            UsbAdbProtocol.CMD_WRTE -> {
                // arg0 = device's localId, arg1 = host's localId
                val localId = msg.arg1   // host's localId
                val remoteId = msg.arg0  // device's localId
                Log.d(TAG, "<<< WRTE localId=$localId remoteId=$remoteId dataLen=${msg.data.size}")
                val stream = streams[localId]
                if (stream != null) {
                    stream.onData(msg.data)
                    // Send OKAY to acknowledge the write — 走直发路径，不经流的写锁
                    // （否则读线程会被正在等流控的业务写阻塞，见 sendAck 注释）
                    sendAck(UsbAdbProtocol.okayMessage(localId, remoteId))
                    Log.d(TAG, ">>> OKAY sent for WRTE localId=$localId")
                } else {
                    Log.w(TAG, "<<< WRTE for unknown stream $localId, streams=${streams.keys}")
                }
            }
            UsbAdbProtocol.CMD_CLSE -> {
                // arg0 = device's localId, arg1 = host's localId
                val localId = msg.arg1   // host's localId
                Log.d(TAG, "<<< CLSE id=$localId, streams=${streams.keys}")
                val stream = streams.remove(localId)
                if (stream != null) {
                    stream.onClosed()
                } else {
                    Log.w(TAG, "<<< CLSE for unknown stream $localId")
                }
            }
            UsbAdbProtocol.CMD_CNXN -> {
                Log.d(TAG, "<<< CNXN (unexpected reconnection)")
                connected = true
            }
            UsbAdbProtocol.CMD_AUTH -> {
                Log.w(TAG, "<<< AUTH (unexpected after connection)")
            }
            else -> {
                Log.w(TAG, "<<< Unknown: ${UsbAdbProtocol.commandName(msg.command)}")
            }
        }
    }
}
