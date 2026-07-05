package com.wearadb.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 高级 ADB shell 操作。
 * 所有操作通过 libadb-android 的 AdbStream 执行。
 */
object AdvancedOps {

    /**
     * 通用命令执行：打开 shell → 发送命令 → 读取输出 → 关闭。
     */
    private suspend fun runCommand(manager: AbsAdbConnectionManager, command: String, timeoutMs: Long = 15000): String {
        return try {
            val stream = manager.openStream(LocalServices.SHELL)
            val os = stream.openOutputStream()
            os.write("$command\n".toByteArray())
            os.flush()

            val sb = StringBuilder()
            val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    sb.appendLine(line)
                } else {
                    Thread.sleep(50)
                }
            }

            try { stream.close() } catch (_: Exception) {}
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ── Reboot ──
    suspend fun reboot(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "reboot")

    suspend fun rebootRecovery(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "reboot recovery")

    suspend fun rebootBootloader(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "reboot bootloader")

    suspend fun shutdown(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "reboot -p")

    // ── Screenshot ──

    /**
     * 截图入口。
     * 首选方案：exec 通道直接读 PNG（通道关闭自动 EOF，无需等待）
     * 兜底方案：marker 检测的文件方案（screencap 写文件 → echo marker → cat）
     */
    suspend fun screenshot(manager: AbsAdbConnectionManager): ByteArray? {
        return try {
            // 方案 A（首选）: exec 通道直接输出 PNG → 读到 EOF
            android.util.Log.d("Screenshot", "Trying exec stdout method...")
            val result = screenshotViaExec(manager)
            if (result != null) return result

            // 方案 B（兜底）: 文件 + marker 方案
            android.util.Log.d("Screenshot", "Exec method failed, trying file+marker method...")
            screenshotViaFile(manager)
        } catch (e: Exception) {
            android.util.Log.e("Screenshot", "Exception: ${e.message}", e)
            null
        }
    }

    /**
     * 方案 A（首选）: 通过 exec 通道直接输出 PNG 到 stdout。
     * exec: 通道执行完毕后自动关闭，流会收到 EOF，不需要 marker 也不需要固定等待。
     * 如果 exec 不可用，fallback 到 shell + exit。
     */
    private suspend fun screenshotViaExec(manager: AbsAdbConnectionManager): ByteArray? {
        // 尝试 exec 通道（命令执行完后通道自动关闭 → EOF）
        try {
            val stream = manager.openStream("exec:screencap -p")
            val result = readPngFromExecStream(stream, "exec")
            try { stream.close() } catch (_: Exception) {}
            if (result != null) return result
        } catch (e: Exception) {
            android.util.Log.d("Screenshot", "exec: channel failed: ${e.message}")
        }

        // fallback: shell + exit（发送 exit 让 shell 关闭 → EOF）
        try {
            val stream = manager.openStream(LocalServices.SHELL)
            val os = stream.openOutputStream()
            os.write("screencap -p; exit\n".toByteArray())
            os.flush()
            val result = readPngFromExecStream(stream, "shell+exit")
            try { stream.close() } catch (_: Exception) {}
            if (result != null) return result
        } catch (e: Exception) {
            android.util.Log.d("Screenshot", "shell+exit failed: ${e.message}")
        }

        return null
    }

    /**
     * 从 exec 类型的流中读取 PNG 数据（等待 EOF，不依赖 marker）。
     * 适用于 exec: 通道（自动 EOF）或 shell + exit（exit 触发 EOF）。
     */
    private fun readPngFromExecStream(stream: AdbStream, tag: String): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val inputStream = stream.openInputStream()
        val buf = ByteArray(65536)
        val startTime = System.currentTimeMillis()
        var lastDataTime = startTime
        var totalRead = 0

        android.util.Log.d("Screenshot", "$tag: reading stream (waiting for EOF)...")
        while (System.currentTimeMillis() - startTime < 15000) {
            val available = try { inputStream.available() } catch (_: Exception) { 0 }
            if (available > 0) {
                val n = inputStream.read(buf)
                if (n < 0) {
                    android.util.Log.d("Screenshot", "$tag: EOF at totalRead=$totalRead")
                    break
                }
                buffer.write(buf, 0, n)
                totalRead += n
                lastDataTime = System.currentTimeMillis()
                if (totalRead % (256 * 1024) < n) {
                    android.util.Log.d("Screenshot", "$tag: read ${totalRead / 1024}KB so far")
                }
            } else {
                // 有数据后 5 秒无新数据，提前退出
                if (buffer.size() > 0 && System.currentTimeMillis() - lastDataTime > 5000) {
                    android.util.Log.d("Screenshot", "$tag: 5s idle after data, total=${buffer.size()}")
                    break
                }
                Thread.sleep(50)
            }
        }

        val bytes = buffer.toByteArray()
        android.util.Log.d("Screenshot", "$tag: total ${bytes.size} bytes, first32=${bytes.take(32).joinToString("") { "%02x".format(it) }}")

        // 找到 PNG 签名，跳过 shell 回显前缀
        val pngStart = findPngSignature(bytes)
        if (pngStart < 0) {
            android.util.Log.e("Screenshot", "$tag: no PNG signature found, rawHex=${bytes.take(64).joinToString("") { "%02x".format(it) }}")
            return null
        }
        android.util.Log.d("Screenshot", "$tag: PNG found at offset $pngStart")

        val pngData = bytes.copyOfRange(pngStart, bytes.size)

        // 截取到 IEND（避免尾部的 shell prompt 等杂数据）
        val trimmed = trimToIend(pngData)
        if (trimmed != null && trimmed.size > 64) {
            android.util.Log.d("Screenshot", "$tag: success! PNG ${trimmed.size} bytes, last12=${trimmed.takeLast(12).joinToString("") { "%02x".format(it) }}")
            logPngDimensions(trimmed, tag)
            return trimmed
        }

        // 如果 IEND 检测失败但数据以 PNG 签名开头且足够大，直接返回
        if (pngData.size > 64) {
            android.util.Log.w("Screenshot", "$tag: IEND not found, returning raw PNG ${pngData.size} bytes")
            logPngDimensions(pngData, tag)
            return pngData
        }

        android.util.Log.e("Screenshot", "$tag: PNG too small (${pngData.size} bytes)")
        return null
    }

    /**
     * 从 PNG 数据中截取到 IEND 标记（含 4 字节 CRC）。
     * 去掉尾部可能存在的 shell prompt 等非 PNG 数据。
     * 从尾部往前搜索，找到第一个 IEND 即可（IEND 是 PNG 最后一个 chunk）。
     */
    private fun trimToIend(data: ByteArray): ByteArray? {
        if (data.size < 12) return null
        // IEND chunk = [4字节长度=0][4字节"IEND"][4字节CRC]，共12字节
        // "IEND" 字符串在 data.size - 8 的位置
        val searchEnd = data.size - 8  // IEND 字符串的起始位置
        val searchRange = minOf(searchEnd, 32768)
        android.util.Log.d("Screenshot", "trimToIend: searching up to $searchRange bytes from end, data.size=${data.size}")
        for (i in searchEnd downTo searchEnd - searchRange) {
            if (i < 0) break
            if (data[i] == 0x49.toByte() &&      // 'I'
                data[i + 1] == 0x45.toByte() &&  // 'E'
                data[i + 2] == 0x4E.toByte() &&  // 'N'
                data[i + 3] == 0x44.toByte()) {  // 'D'
                val result = data.copyOf(i + 12)
                android.util.Log.d("Screenshot", "trimToIend: found IEND at offset $i, result=${result.size} bytes")
                return result
            }
        }
        android.util.Log.w("Screenshot", "trimToIend: IEND not found")
        return null
    }

    /**
     * 方案 B（兜底）: marker 检测的文件方案。
     * screencap 写文件 → echo marker → 等 marker 出现 → cat 文件。
     * marker 只在 screencap 成功完成后才会出现（通过 && 连接）。
     */
    private suspend fun screenshotViaFile(manager: AbsAdbConnectionManager): ByteArray? {
        val paths = listOf(
            "/sdcard/_wearadb_ss.png",
            "/data/local/tmp/_wearadb_ss.png"
        )
        for (tmpPath in paths) {
            val result = screenshotToPathViaMarker(manager, tmpPath)
            if (result != null) return result
        }
        return null
    }

    /**
     * 文件方案：screencap && echo MARKER → 等 marker → cat 文件 → 清理。
     * 通过 && 连接，marker 只在 screencap 成功后才出现，不会被 PNG 二进制数据干扰。
     */
    private suspend fun screenshotToPathViaMarker(manager: AbsAdbConnectionManager, tmpPath: String): ByteArray? {
        val marker = "__SS_DONE_${System.nanoTime()}__"

        // 1. screencap 写文件，成功后输出 marker
        android.util.Log.d("Screenshot", "file: screencap -> $tmpPath")
        val stream = manager.openStream(LocalServices.SHELL)
        val os = stream.openOutputStream()
        os.write("screencap -p $tmpPath && echo $marker\n".toByteArray())
        os.flush()

        // 2. 读到 marker 表示 screencap 完成
        val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
        val waitStart = System.currentTimeMillis()
        var markerFound = false
        while (System.currentTimeMillis() - waitStart < 10000) {
            if (stream.openInputStream().available() > 0) {
                val line = reader.readLine() ?: break
                if (line.trim() == marker) {
                    markerFound = true
                    break
                }
            } else {
                Thread.sleep(50)
            }
        }
        try { stream.close() } catch (_: Exception) {}
        android.util.Log.d("Screenshot", "file: markerFound=$markerFound (${System.currentTimeMillis() - waitStart}ms)")

        if (!markerFound) {
            android.util.Log.e("Screenshot", "file: marker not found for $tmpPath")
            cleanupTmpFile(manager, tmpPath)
            return null
        }

        // 3. cat 读取文件
        android.util.Log.d("Screenshot", "file: cat $tmpPath")
        val result = catPngFile(manager, tmpPath)
        if (result != null) {
            logPngDimensions(result, "file")
        } else {
            cleanupTmpFile(manager, tmpPath)
        }
        return result
    }

    private suspend fun cleanupTmpFile(manager: AbsAdbConnectionManager, tmpPath: String) {
        try {
            val s = manager.openStream(LocalServices.SHELL)
            val o = s.openOutputStream()
            o.write("rm -f $tmpPath\n".toByteArray())
            o.flush()
            Thread.sleep(100)
            try { s.close() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /**
     * 从 PNG IHDR 读取宽高并记录日志
     */
    private fun logPngDimensions(data: ByteArray, tag: String) {
        try {
            if (data.size >= 24 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()) {
                val ihdrBytes = data.slice(0..23).joinToString(" ") { "%02X".format(it) }
                android.util.Log.d("Screenshot", "$tag: IHDR raw bytes: $ihdrBytes")

                val w = ((data[16].toInt() and 0xFF) shl 24) or
                        ((data[17].toInt() and 0xFF) shl 16) or
                        ((data[18].toInt() and 0xFF) shl 8) or
                        (data[19].toInt() and 0xFF)
                val h = ((data[20].toInt() and 0xFF) shl 24) or
                        ((data[21].toInt() and 0xFF) shl 16) or
                        ((data[22].toInt() and 0xFF) shl 8) or
                        (data[23].toInt() and 0xFF)
                android.util.Log.d("Screenshot", "$tag: PNG dimensions=${w}x${h}, size=${data.size}, ratio=${"%.1f".format(data.size.toDouble() / (w * h * 3))}")

                // 检查 IEND 是否在正确位置（文件末尾 12 字节）
                val lastChunkType = if (data.size >= 12) {
                    String(data, data.size - 8, 4)
                } else "?"
                android.util.Log.d("Screenshot", "$tag: last chunk type='$lastChunkType', ends_with_IEND=${data.size >= 12 && data[data.size - 8] == 0x49.toByte()}")

                if (w > 10000 || h > 10000 || w <= 0 || h <= 0) {
                    android.util.Log.e("Screenshot", "$tag: INVALID dimensions! PNG data may be corrupted")
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * cat 文件并读取 PNG 数据（用于文件方案的第二步）
     */
    private suspend fun catPngFile(manager: AbsAdbConnectionManager, tmpPath: String): ByteArray? {
        val stream = manager.openStream(LocalServices.SHELL)
        val os = stream.openOutputStream()
        os.write("cat $tmpPath; exit\n".toByteArray())
        os.flush()

        val result = readPngFromExecStream(stream, "cat")
        try { stream.close() } catch (_: Exception) {}

        // 清理临时文件
        cleanupTmpFile(manager, tmpPath)

        return result
    }

    /**
     * 在字节数组中查找 PNG 签名 (89 50 4E 47) 的位置
     */
    private fun findPngSignature(data: ByteArray): Int {
        if (data.size < 8) return -1
        for (i in 0..minOf(data.size - 8, 512)) {
            if (data[i] == 0x89.toByte() &&
                data[i + 1] == 0x50.toByte() &&
                data[i + 2] == 0x4E.toByte() &&
                data[i + 3] == 0x47.toByte()) {
                return i
            }
        }
        return -1
    }

    // ── Input Events ──
    suspend fun tap(manager: AbsAdbConnectionManager, x: Int, y: Int): String =
        runCommand(manager, "input tap $x $y")

    suspend fun swipe(manager: AbsAdbConnectionManager, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): String =
        runCommand(manager, "input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun keyEvent(manager: AbsAdbConnectionManager, keycode: Int): String =
        runCommand(manager, "input keyevent $keycode")

    suspend fun text(manager: AbsAdbConnectionManager, text: String): String =
        runCommand(manager, "input text \"$text\"")

    // ── System Info ──
    suspend fun getProp(manager: AbsAdbConnectionManager, key: String): String =
        runCommand(manager, "getprop $key")

    suspend fun setProp(manager: AbsAdbConnectionManager, key: String, value: String): String =
        runCommand(manager, "setprop $key $value")

    // ── Display ──
    suspend fun getScreenBrightness(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "settings get system screen_brightness")

    suspend fun setScreenBrightness(manager: AbsAdbConnectionManager, value: Int): String =
        runCommand(manager, "settings put system screen_brightness $value")

    suspend fun screenOn(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "input keyevent 26")

    suspend fun screenOff(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "input keyevent 26")

    // ── WiFi ──
    suspend fun enableWifi(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "svc wifi enable")

    suspend fun disableWifi(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "svc wifi disable")

    // ── Bluetooth ──
    suspend fun enableBluetooth(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "svc bluetooth enable")

    suspend fun disableBluetooth(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "svc bluetooth disable")

    // ── Volume ──
    suspend fun volumeUp(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "input keyevent 24")

    suspend fun volumeDown(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "input keyevent 25")

    suspend fun volumeMute(manager: AbsAdbConnectionManager): String =
        runCommand(manager, "input keyevent 164")

    // ── Clipboard ──
    suspend fun getClipboard(manager: AbsAdbConnectionManager): String {
        // service call clipboard 2 = getClipboardText (Android 10+)
        val result = runCommand(manager, "service call clipboard 2 i32 1")
        return parseServiceCallString(result)
    }

    suspend fun setClipboard(manager: AbsAdbConnectionManager, text: String): String {
        // 用 cmd clipboard write (Android 12+) 或 service call fallback
        val escaped = text.replace("\"", "\\\"")
        val result = runCommand(manager, "cmd clipboard write \"$escaped\"")
        if (result.isNotEmpty() && !result.contains("Error")) return result
        // fallback: service call clipboard 1
        return runCommand(manager, "service call clipboard 1 i32 1 s16 \"$escaped\" i32 0")
    }

    /**
     * 解析 service call clipboard 2 返回的 Parcel 字符串。
     * 输出格式示例:
     *   Result: Parcel(
     *     0x00000000: 00000000 00000001 00000000 00000000
     *     0x00000010: 00050048 0065006c 006c006f 00000000
     *   )
     * 提取 UTF-16LE 文本。
     */
    private fun parseServiceCallString(raw: String): String {
        return try {
            val lines = raw.lines()
            val sb = StringBuilder()
            for (line in lines) {
                val trimmed = line.trim()
                // 匹配包含 hex data 的行: "0x000000XX: XXXXXXXX ..."
                if (trimmed.startsWith("0x") && trimmed.contains(":")) {
                    val hexPart = trimmed.substringAfter(":").trim()
                    val bytes = hexPart.split("\\s+".toRegex())
                    for (word in bytes) {
                        if (word.length == 8) {
                            // 每个 word 是 4 字节，UTF-16LE 编码
                            val b0 = word.substring(6, 8).toIntOrNull(16) ?: continue
                            val b1 = word.substring(4, 6).toIntOrNull(16) ?: continue
                            if (b0 == 0 && b1 == 0) continue
                            val char = ((b1 shl 8) or b0).toChar()
                            if (char.code in 32..126 || char.code in 0x4E00..0x9FFF) {
                                sb.append(char)
                            }
                        }
                    }
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ── File System ──
    suspend fun chmod(manager: AbsAdbConnectionManager, path: String, mode: String): String =
        runCommand(manager, "chmod $mode $path")

    suspend fun chown(manager: AbsAdbConnectionManager, path: String, owner: String): String =
        runCommand(manager, "chown $owner $path")

    // Common Android keycodes
    object KeyCodes {
        const val HOME = 3
        const val BACK = 4
        const val POWER = 26
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val VOLUME_MUTE = 164
        const val ENTER = 66
        const val DELETE = 67
        const val TAB = 61
        const val ESCAPE = 111
        const val MEDIA_PLAY_PAUSE = 85
        const val MEDIA_NEXT = 87
        const val MEDIA_PREVIOUS = 88
    }
}
