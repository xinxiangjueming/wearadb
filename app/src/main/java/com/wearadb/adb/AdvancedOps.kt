package com.wearadb.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
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
    suspend fun screenshot(manager: AbsAdbConnectionManager): ByteArray? {
        return try {
            val stream = manager.openStream(LocalServices.SHELL)
            val os = stream.openOutputStream()
            os.write("screencap -p\n".toByteArray())
            os.flush()

            val buffer = java.io.ByteArrayOutputStream()
            val `is` = stream.openInputStream()
            val buf = ByteArray(8192)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 10000) {
                if (`is`.available() > 0) {
                    val n = `is`.read(buf)
                    if (n < 0) break
                    buffer.write(buf, 0, n)
                } else {
                    Thread.sleep(50)
                }
            }

            try { stream.close() } catch (_: Exception) {}
            val bytes = buffer.toByteArray()
            if (bytes.size > 8) bytes else null
        } catch (e: Exception) {
            null
        }
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
