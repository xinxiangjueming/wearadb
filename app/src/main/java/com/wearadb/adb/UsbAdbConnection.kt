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

                val signature = Signature.getInstance("SHA256withRSA")
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
        val pubKeyBytes = certificate.publicKey.encoded
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
     */
    fun openStream(destination: String): UsbAdbStream {
        val localId = nextLocalId.getAndIncrement()
        val stream = UsbAdbStream(localId, destination)
        pendingOpens[localId] = stream
        streams[localId] = stream
        Log.d(TAG, "openStream: localId=$localId dest=$destination, totalStreams=${streams.size}")

        sendMessage(UsbAdbProtocol.openMessage(localId, destination))
        log(">>> OPEN id=$localId dest=$destination")

        // Wait for OKAY response (with remoteId)
        val opened = stream.waitForOpen(10000)
        Log.d(TAG, "openStream: localId=$localId opened=$opened")

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
     * Send ADB message as TWO separate bulk transfers (header + payload).
     * ADB-SafeScan: "writing header+payload as a single buffer produces
     * different results from writing them separately"
     */
    internal fun sendMessage(msg: AdbMessage) {
        val header = msg.toHeaderBytes()
        val payload = if (msg.data.isNotEmpty()) msg.data else null
        transport.writeMessage(header, payload)
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
                    // Send OKAY to acknowledge the write
                    sendMessage(UsbAdbProtocol.okayMessage(localId, remoteId))
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
