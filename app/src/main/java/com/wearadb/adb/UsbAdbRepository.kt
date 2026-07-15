package com.wearadb.adb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for wired USB ADB operations.
 * Integrates UsbAdbManager with the rest of the app.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class UsbAdbRepository @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "UsbAdbRepository"
    }

    // ── State ──
    private val _connectionState = MutableStateFlow(UsbAdbConnectionState.DISCONNECTED)
    val connectionState: StateFlow<UsbAdbConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<UsbAdbDeviceInfo?>(null)
    val connectedDevice: StateFlow<UsbAdbDeviceInfo?> = _connectedDevice.asStateFlow()

    private val _connectLog = MutableStateFlow("")
    val connectLog: StateFlow<String> = _connectLog.asStateFlow()
    private val logLines = mutableListOf<String>()

    // ── Deferred Manager (RSA key gen off main thread) ──
    private data class AdbComponents(
        val manager: UsbAdbManager,
        val privateKey: PrivateKey,
        val certificate: Certificate
    )
    private val initDeferred = CompletableDeferred<AdbComponents>()

    init {
        // Move RSA key generation + file IO off main thread
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val (pk, cert) = loadOrGenerateKeyPair()
                val mgr = UsbAdbManager(appContext, pk, cert) { msg ->
                    synchronized(logLines) {
                        logLines.add(msg)
                        _connectLog.value = logLines.joinToString("\n")
                    }
                }
                initDeferred.complete(AdbComponents(mgr, pk, cert))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Async init failed", e)
                initDeferred.completeExceptionally(e)
            }
        }
    }

    /** Await manager initialization (runs on IO, never blocks main thread). */
    private suspend fun awaitManager(): UsbAdbManager {
        return withTimeout(30_000) { initDeferred.await().manager }
    }

    // ── Scanning ──

    suspend fun scanDevices(): List<UsbAdbDeviceInfo> = withContext(Dispatchers.IO) {
        awaitManager().scanDevices()
    }

    // ── Connection ──

    suspend fun connect(deviceInfo: UsbAdbDeviceInfo): Boolean = withContext(Dispatchers.IO) {
        synchronized(logLines) { logLines.clear(); _connectLog.value = "" }
        _connectionState.value = UsbAdbConnectionState.CONNECTING

        val mgr = awaitManager()
        val success = mgr.connect(deviceInfo)
        if (success) {
            _connectionState.value = UsbAdbConnectionState.CONNECTED
            _connectedDevice.value = deviceInfo
        } else {
            _connectionState.value = UsbAdbConnectionState.ERROR
            _connectedDevice.value = null
        }
        success
    }

    suspend fun disconnect() {
        try {
            awaitManager().disconnect()
        } catch (_: Exception) {}
        _connectionState.value = UsbAdbConnectionState.DISCONNECTED
        _connectedDevice.value = null
    }

    val isConnected: Boolean
        get() = try {
            initDeferred.getCompleted().manager.isConnected
        } catch (_: Exception) { false }

    // ── Commands ──

    /**
     * Execute a shell command over USB ADB.
     * Returns the command output as a string.
     */
    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext "未连接"
        try {
            val stream = conn.openShell(command)
            val result = stream.readAll(15000)
            conn.closeStream(stream)
            result
        } catch (e: Exception) {
            "执行失败: ${e.message}"
        }
    }

    /**
     * Get device info via a single combined shell command.
     * Uses the same comprehensive command as the wireless ADB path,
     * and parses with AdbOutputParser for full DeviceInfo fields.
     */
    suspend fun getDeviceInfo(): com.wearadb.data.model.DeviceInfo = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext com.wearadb.data.model.DeviceInfo()

        try {
            android.util.Log.d(TAG, "getDeviceInfo: 开始执行命令...")
            val stream = conn.openShell(
                "echo ==PROPS==; getprop; " +
                "echo ==BATTERY==; dumpsys battery; " +
                "cat /sys/class/power_supply/battery/uevent 2>/dev/null; " +
                "cat /sys/class/power_supply/Battery/uevent 2>/dev/null; " +
                "dumpsys batterystats 2>/dev/null | grep -i 'charge_full\\|charge_full_design\\|capacity'; " +
                "echo ==DISPLAY==; wm size; wm density; " +
                "echo ==MEM==; cat /proc/meminfo | head -3; " +
                "echo ==UPTIME==; uptime; " +
                "echo ==STORAGE==; df -h /data 2>/dev/null || df -h /storage/emulated 2>/dev/null || df -h 2>/dev/null | head -5"
            )
            android.util.Log.d(TAG, "getDeviceInfo: stream opened, reading...")
            val raw = stream.readAll(30000)
            conn.closeStream(stream)
            android.util.Log.d(TAG, "getDeviceInfo: raw length=${raw.length}, content=${raw.take(300)}")

            val result = AdbOutputParser.parseDeviceInfo(raw)
            val (storageTotal, storageUsed, storageFree) = AdbOutputParser.parseStorageInfo(raw)
            result.copy(storageTotal = storageTotal, storageUsed = storageUsed, storageFree = storageFree)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getDeviceInfo failed: ${e.message}")
            com.wearadb.data.model.DeviceInfo()
        }
    }

    /**
     * Get installed packages via shell command.
     */
    suspend fun getInstalledPackages(): List<com.wearadb.data.model.AppEntry> = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext emptyList()
        try {
            val combined = conn.openShell(
                "echo ==FULL==; pm list packages -f; echo ==SYSTEM==; pm list packages -s; echo ==THIRD==; pm list packages -3; echo ==DISABLED==; pm list packages -d"
            ).readAll(15000)
            val sections = combined.split(Regex("==FULL==|==SYSTEM==|==THIRD==|==DISABLED=="))
            val fullOutput = sections.getOrElse(1) { "" }.trim()
            val systemOutput = sections.getOrElse(2) { "" }.trim()
            val thirdPartyOutput = sections.getOrElse(3) { "" }.trim()
            val disabledOutput = sections.getOrElse(4) { "" }.trim()
            val systemPkgs = systemOutput.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toSet()
            val thirdPartyPkgs = thirdPartyOutput.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toSet()
            val disabledPkgs = disabledOutput.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toSet()
            AdbOutputParser.parsePackageListWithFilter(fullOutput, systemPkgs, thirdPartyPkgs, disabledPkgs)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getInstalledPackages failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Take a screenshot over wired USB ADB.
     * Runs `screencap -p` and reads the raw PNG bytes from the stream.
     */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        val conn = awaitManager().getConnection() ?: return@withContext null
        try {
            android.util.Log.d(TAG, "screenshot: opening shell stream...")
            val stream = conn.openShell("screencap -p")
            val rawBytes = stream.readAllBytes(15000)
            conn.closeStream(stream)
            android.util.Log.d(TAG, "screenshot: read ${rawBytes.size} bytes")

            // Find PNG signature (skip any shell echo prefix)
            val pngStart = findPngSignature(rawBytes)
            if (pngStart < 0) {
                android.util.Log.e(TAG, "screenshot: no PNG signature found")
                return@withContext null
            }
            val pngData = if (pngStart > 0) rawBytes.copyOfRange(pngStart, rawBytes.size) else rawBytes

            // Trim to IEND marker (remove trailing shell prompt garbage)
            val trimmed = trimToIend(pngData)
            val result = trimmed ?: pngData
            if (result.size > 64) {
                android.util.Log.d(TAG, "screenshot: success, ${result.size} bytes")
                result
            } else {
                android.util.Log.e(TAG, "screenshot: PNG too small (${result.size} bytes)")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "screenshot failed: ${e.message}")
            null
        }
    }

    private fun findPngSignature(data: ByteArray): Int {
        if (data.size < 8) return -1
        for (i in 0..minOf(data.size - 8, 512)) {
            if (data[i] == 0x89.toByte() && data[i + 1] == 0x50.toByte() &&
                data[i + 2] == 0x4E.toByte() && data[i + 3] == 0x47.toByte()) return i
        }
        return -1
    }

    private fun trimToIend(data: ByteArray): ByteArray? {
        if (data.size < 12) return null
        val searchRange = minOf(data.size - 12, 4096)
        for (i in data.size - 12 downTo data.size - 12 - searchRange) {
            if (i < 0) break
            if (data[i] == 0x49.toByte() && data[i + 1] == 0x45.toByte() &&
                data[i + 2] == 0x4E.toByte() && data[i + 3] == 0x44.toByte()) {
                return data.copyOf(i + 12)
            }
        }
        return null
    }

    // ── 文件推送 ──

    suspend fun pushFile(localFile: File, remotePath: String): String = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "pushFile: ${localFile.name} (${localFile.length()} bytes) -> $remotePath")
        val conn = awaitManager().getConnection() ?: return@withContext "未连接"
        try {
            val stream = conn.openSync()

            // SEND header — single buffer, write via WRTE
            val pathBytes = "$remotePath,0644".toByteArray()
            val sendBuf = java.nio.ByteBuffer.allocate(8 + pathBytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            sendBuf.putInt(0x444e4553)
            sendBuf.putInt(pathBytes.size)
            sendBuf.put(pathBytes)
            stream.write(sendBuf.array(), conn)
            android.util.Log.d(TAG, "pushFile: SEND sent, ${sendBuf.array().size} bytes")

            // DATA chunks
            val chunkSize = 64 * 1024
            val buf = ByteArray(chunkSize)
            var totalSent = 0L
            var chunkCount = 0
            localFile.inputStream().use { fis ->
                var bytesRead: Int
                while (fis.read(buf).also { bytesRead = it } != -1) {
                    val packet = java.nio.ByteBuffer.allocate(8 + bytesRead).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    packet.putInt(0x41544144)
                    packet.putInt(bytesRead)
                    packet.put(buf, 0, bytesRead)
                    stream.write(packet.array(), conn)
                    totalSent += bytesRead
                    chunkCount++
                    if (chunkCount % 100 == 0) {
                        android.util.Log.d(TAG, "pushFile: DATA #$chunkCount, totalSent=$totalSent")
                    }
                }
            }
            android.util.Log.d(TAG, "pushFile: all DATA sent, totalSent=$totalSent, chunks=$chunkCount")

            // DONE
            val doneBuf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            doneBuf.putInt(0x454e4f44)
            doneBuf.putInt((System.currentTimeMillis() / 1000).toInt())
            stream.write(doneBuf.array(), conn)
            android.util.Log.d(TAG, "pushFile: DONE sent")

            // Read response — must wait for device reply
            val resp = ByteArray(8)
            var totalRead = 0
            while (totalRead < 8) {
                val data = stream.readBlocking(5000) ?: break
                val toCopy = minOf(data.size, 8 - totalRead)
                data.copyInto(resp, totalRead, 0, toCopy)
                totalRead += toCopy
            }
            val respCmd = littleEndianToInt(resp, 0)
            android.util.Log.d(TAG, "pushFile: response totalRead=$totalRead, respCmd=0x${respCmd.toString(16)}")

            conn.closeStream(stream)

            when (respCmd) {
                0x59414b4f -> "推送成功: $remotePath"
                0x4c494146 -> "推送失败"
                else -> "推送完成"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pushFile exception", e)
            "推送异常: ${e.message}"
        }
    }

    // ── 安装 APK ──

    suspend fun installApk(apkData: ByteArray): String = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "installApk(ByteArray): size=${apkData.size}")
        try {
            val tmpPath = "/data/local/tmp/_wearadb_install_${System.currentTimeMillis()}.apk"
            // Write bytes to temp file, then push
            val tmpFile = File(appContext.cacheDir, "wearadb_usb_install.apk")
            tmpFile.writeBytes(apkData)
            val pushResult = pushFile(tmpFile, tmpPath)
            tmpFile.delete()
            android.util.Log.d(TAG, "installApk(ByteArray): pushResult=$pushResult")
            if (!pushResult.contains("成功")) return@withContext "推送失败: $pushResult"

            val installResult = executeCommand("pm install -r $tmpPath")
            android.util.Log.d(TAG, "installApk(ByteArray): installResult=$installResult")
            executeCommand("rm -f $tmpPath")

            val clean = installResult.trim()
            when {
                clean.contains("Success") -> "安装成功"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "installApk(ByteArray) exception", e)
            "安装异常: ${e.message}"
        }
    }

    suspend fun installApk(apkFile: File): String = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "installApk: ${apkFile.name}, size=${apkFile.length()}")
        try {
            val tmpPath = "/data/local/tmp/_wearadb_install_${System.currentTimeMillis()}.apk"
            val pushResult = pushFile(apkFile, tmpPath)
            android.util.Log.d(TAG, "installApk: pushResult=$pushResult")
            if (!pushResult.contains("成功")) return@withContext "推送失败: $pushResult"

            val installResult = executeCommand("pm install -r $tmpPath")
            android.util.Log.d(TAG, "installApk: installResult=$installResult")
            executeCommand("rm -f $tmpPath")

            val clean = installResult.trim()
            when {
                clean.contains("Success") -> "安装成功"
                clean.isEmpty() -> "安装失败: 无响应"
                else -> "安装失败: $clean"
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "installApk exception", e)
            "安装异常: ${e.message}"
        }
    }

    // ── 文件管理 ──

    suspend fun listFiles(path: String): List<com.wearadb.data.model.FileEntry> = withContext(Dispatchers.IO) {
        val output = executeCommand("ls -Lla $path 2>&1")
        android.util.Log.d(TAG, "listFiles($path) output length=${output.length}")
        com.wearadb.adb.AdbOutputParser.parseFileListing(output, path)
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        executeCommand("cat $path")
    }

    suspend fun deleteFile(path: String): String = withContext(Dispatchers.IO) {
        executeCommand("rm -rf $path")
    }

    suspend fun createDirectory(path: String): String = withContext(Dispatchers.IO) {
        executeCommand("mkdir -p $path")
    }

    suspend fun pullFile(remotePath: String): Pair<Boolean, ByteArray?> = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "pullFile: $remotePath")
        val conn = awaitManager().getConnection() ?: return@withContext false to null
        try {
            val stream = conn.openSync()

            // RECV header — single buffer
            val pathBytes = remotePath.toByteArray()
            val recvBuf = java.nio.ByteBuffer.allocate(8 + pathBytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            recvBuf.putInt(0x56434552) // RECV
            recvBuf.putInt(pathBytes.size)
            recvBuf.put(pathBytes)
            stream.write(recvBuf.array(), conn)
            android.util.Log.d(TAG, "pullFile: RECV sent, ${recvBuf.array().size} bytes")

            // Buffered reader: WRTE payloads → continuous byte stream
            val dataBuffer = java.io.ByteArrayOutputStream()
            var totalReceived = 0L
            var dataOffset = 0 // tracks consumed bytes in dataBuffer

            fun refillBuffer(): Boolean {
                // Discard consumed bytes
                if (dataOffset > 0) {
                    val remaining = dataBuffer.toByteArray().copyOfRange(dataOffset, dataBuffer.size())
                    dataBuffer.reset()
                    dataBuffer.write(remaining)
                    dataOffset = 0
                }
                // Read more WRTE payloads until we have enough
                while (dataBuffer.size() - dataOffset < 8) {
                    val chunk = stream.readBlocking(10000) ?: return false
                    dataBuffer.write(chunk)
                }
                return true
            }

            fun readBytes(n: Int): ByteArray? {
                // Ensure buffer has enough data, refill if needed
                while (dataBuffer.size() - dataOffset < n) {
                    val chunk = stream.readBlocking(10000) ?: return null
                    dataBuffer.write(chunk)
                }
                val buf = dataBuffer.toByteArray()
                val result = buf.copyOfRange(dataOffset, dataOffset + n)
                dataOffset += n
                return result
            }

            // Read SYNC DATA chunks
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                if (!refillBuffer()) break
                val headerBytes = readBytes(8) ?: break
                val cmd = littleEndianToInt(headerBytes, 0)
                val size = littleEndianToInt(headerBytes, 4)
                android.util.Log.d(TAG, "pullFile: cmd=0x${cmd.toString(16)}, size=$size")

                when (cmd) {
                    0x41544144 -> { // DATA
                        var remaining = size
                        while (remaining > 0) {
                            val chunk = readBytes(remaining) ?: break
                            output.write(chunk)
                            totalReceived += chunk.size
                            remaining -= chunk.size
                        }
                        if (totalReceived % (1024 * 1024) < 65536) {
                            android.util.Log.d(TAG, "pullFile: received ${totalReceived / 1024}KB")
                        }
                    }
                    0x454e4f44 -> { // DONE
                        android.util.Log.d(TAG, "pullFile: DONE, totalReceived=$totalReceived")
                        break
                    }
                    0x4c494146 -> { // FAIL
                        android.util.Log.w(TAG, "pullFile: FAIL")
                        conn.closeStream(stream)
                        return@withContext false to null
                    }
                    else -> break
                }
            }

            conn.closeStream(stream)
            android.util.Log.d(TAG, "pullFile: success, ${output.size()} bytes")
            true to output.toByteArray()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pullFile exception", e)
            false to null
        }
    }

    private fun littleEndianToInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun destroy() {
        try { initDeferred.getCompleted().manager.destroy() } catch (_: Exception) {}
    }

    // ── Key management ──

    private fun loadOrGenerateKeyPair(): Pair<PrivateKey, Certificate> {
        val pk = readPrivateKey()
        val cert = readCertificate()
        if (pk != null && cert != null) return pk to cert

        // Generate new key pair
        val keyPairGen = java.security.KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, java.security.SecureRandom.getInstance("SHA1PRNG"))
        val keyPair = keyPairGen.generateKeyPair()
        val newPk = keyPair.private
        val newCert = generateSelfSignedCert(keyPair.public, newPk)

        writePrivateKey(newPk)
        writeCertificate(newCert)
        return newPk to newCert
    }

    private fun generateSelfSignedCert(publicKey: java.security.PublicKey, privateKey: PrivateKey): java.security.cert.Certificate {
        val subject = javax.security.auth.x500.X500Principal("CN=wear-adb-usb")
        val serial = java.math.BigInteger.ONE
        val notBefore = java.util.Date()
        val notAfter = java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, publicKey
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
        val holder = builder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun readPrivateKey(): PrivateKey? {
        val file = File(appContext.filesDir, "adb_usb_private.key")
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Exception) { null }
    }

    private fun writePrivateKey(key: PrivateKey) {
        File(appContext.filesDir, "adb_usb_private.key").writeBytes(key.encoded)
    }

    private fun readCertificate(): java.security.cert.Certificate? {
        val file = File(appContext.filesDir, "adb_usb_cert.pem")
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } catch (_: Exception) { null }
    }

    private fun writeCertificate(cert: java.security.cert.Certificate) {
        val file = File(appContext.filesDir, "adb_usb_cert.pem")
        FileOutputStream(file).use { os ->
            os.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
            os.write(android.util.Base64.encode(cert.encoded, android.util.Base64.DEFAULT))
            os.write("\n-----END CERTIFICATE-----\n".toByteArray())
        }
    }
}

enum class UsbAdbConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
