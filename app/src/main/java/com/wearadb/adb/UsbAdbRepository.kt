package com.wearadb.adb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    // ── Manager ──
    private val manager: UsbAdbManager

    init {
        val (pk, cert) = loadOrGenerateKeyPair()
        manager = UsbAdbManager(appContext, pk, cert) { msg ->
            synchronized(logLines) {
                logLines.add(msg)
                _connectLog.value = logLines.joinToString("\n")
            }
        }
    }

    // ── Scanning ──

    fun scanDevices(): List<UsbAdbDeviceInfo> = manager.scanDevices()

    // ── Connection ──

    suspend fun connect(deviceInfo: UsbAdbDeviceInfo): Boolean = withContext(Dispatchers.IO) {
        synchronized(logLines) { logLines.clear(); _connectLog.value = "" }
        _connectionState.value = UsbAdbConnectionState.CONNECTING

        val success = manager.connect(deviceInfo)
        if (success) {
            _connectionState.value = UsbAdbConnectionState.CONNECTED
            _connectedDevice.value = deviceInfo
        } else {
            _connectionState.value = UsbAdbConnectionState.ERROR
            _connectedDevice.value = null
        }
        success
    }

    fun disconnect() {
        try {
            manager.disconnect()
        } catch (_: Exception) {}
        _connectionState.value = UsbAdbConnectionState.DISCONNECTED
        _connectedDevice.value = null
    }

    val isConnected: Boolean get() = manager.isConnected

    // ── Commands ──

    /**
     * Execute a shell command over USB ADB.
     * Returns the command output as a string.
     */
    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val conn = manager.getConnection() ?: return@withContext "未连接"
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
        val conn = manager.getConnection() ?: return@withContext com.wearadb.data.model.DeviceInfo()

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
        val conn = manager.getConnection() ?: return@withContext emptyList()
        try {
            val combined = conn.openShell(
                "echo ==FULL==; pm list packages -f; echo ==SYSTEM==; pm list packages -s; echo ==THIRD==; pm list packages -3"
            ).readAll(15000)
            val sections = combined.split(Regex("==FULL==|==SYSTEM==|==THIRD=="))
            val fullOutput = sections.getOrElse(1) { "" }.trim()
            val systemOutput = sections.getOrElse(2) { "" }.trim()
            val thirdPartyOutput = sections.getOrElse(3) { "" }.trim()
            val systemPkgs = systemOutput.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toSet()
            val thirdPartyPkgs = thirdPartyOutput.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toSet()
            AdbOutputParser.parsePackageListWithFilter(fullOutput, systemPkgs, thirdPartyPkgs)
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
        val conn = manager.getConnection() ?: return@withContext null
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

    fun destroy() {
        manager.destroy()
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
