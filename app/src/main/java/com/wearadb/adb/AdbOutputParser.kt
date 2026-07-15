package com.wearadb.adb

import com.wearadb.data.model.AppEntry
import com.wearadb.data.model.DeviceInfo
import com.wearadb.data.model.FileEntry

/**
 * Parses raw ADB shell output into structured data.
 */
object AdbOutputParser {

    fun parseDeviceInfo(output: String): DeviceInfo {
        // 支持两种 getprop 格式: "[key]: [value]" 和 "key=value"
        val props = mutableMapOf<String, String>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // 优先匹配 [key]: [value] 格式（Wear OS）
            if (trimmed.startsWith("[") && trimmed.contains("]: [")) {
                val key = trimmed.substringAfter("[").substringBefore("]:")
                val value = trimmed.substringAfter("]: [").substringBeforeLast("]")
                props[key] = value
            }
            // fallback: key=value 格式
            else if (trimmed.contains("=") && !trimmed.startsWith("[")) {
                val eqIdx = trimmed.indexOf('=')
                val k = trimmed.substring(0, eqIdx).trim()
                val v = trimmed.substring(eqIdx + 1).trim()
                if (k.isNotEmpty()) props[k] = v
            }
        }

        val batteryLine = output.lineSequence()
            .firstOrNull { it.contains("level:") }
        val batteryLevel = batteryLine
            ?.substringAfter("level:")?.substringBefore(",")?.trim()?.toIntOrNull() ?: -1
        val batteryStatus = when {
            output.contains("status: 2") -> "充电中"
            output.contains("status: 3") -> "未充电"
            output.contains("status: 5") -> "充满"
            else -> "未知"
        }
        val batteryHealth = when {
            output.contains("health: 2") -> "良好"
            output.contains("health: 3") -> "过热"
            output.contains("health: 4") -> "已损坏"
            output.contains("health: 5") -> "过压"
            output.contains("health: 6") -> "未知故障"
            output.contains("health: 7") -> "低温"
            else -> ""
        }
        val batteryTechnology = output.lineSequence()
            .firstOrNull { it.trim().startsWith("technology:") }
            ?.substringAfter("technology:")?.trim() ?: ""
        val batteryDesignCapacity = run {
            // 1. uevent 格式: POWER_SUPPLY_CHARGE_FULL_DESIGN=xxxx (µAh)
            val uevent = output.lineSequence()
                .firstOrNull { it.contains("POWER_SUPPLY_CHARGE_FULL_DESIGN=") }
                ?.substringAfter("=")?.trim()?.toLongOrNull()
            if (uevent != null && uevent > 0) return@run (uevent / 1000).toInt()
            // 2. dumpsys batterystats: Charge_full_design: xxxx (µAh)
            val stats = output.lineSequence()
                .firstOrNull { it.trim().startsWith("Charge_full_design:") || it.trim().startsWith("charge_full_design:") }
                ?.substringAfter(":")?.trim()?.replace(Regex("[^0-9]"), "")?.toLongOrNull()
            if (stats != null && stats > 0) return@run if (stats > 100000) (stats / 1000).toInt() else stats.toInt()
            // 3. 旧格式 fallback
            output.lineSequence()
                .firstOrNull { it.contains("design capacity") }
                ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
        }
        val batteryCurrentCapacity = run {
            // 1. uevent 格式: POWER_SUPPLY_CHARGE_FULL=xxxx (µAh)
            val uevent = output.lineSequence()
                .firstOrNull { it.contains("POWER_SUPPLY_CHARGE_FULL=") && !it.contains("DESIGN") }
                ?.substringAfter("=")?.trim()?.toLongOrNull()
            if (uevent != null && uevent > 0) return@run (uevent / 1000).toInt()
            // 2. dumpsys batterystats: Charge_full: xxxx (µAh)
            val stats = output.lineSequence()
                .firstOrNull { it.trim().startsWith("Charge_full:") || it.trim().startsWith("charge_full:") }
                ?.substringAfter(":")?.trim()?.replace(Regex("[^0-9]"), "")?.toLongOrNull()
            if (stats != null && stats > 0) return@run if (stats > 100000) (stats / 1000).toInt() else stats.toInt()
            // 3. 旧格式 fallback
            output.lineSequence()
                .firstOrNull { it.contains("current capacity") || it.contains("charge_counter") }
                ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
        }
        val batteryVoltage = output.lineSequence()
            .firstOrNull { it.trim().startsWith("voltage:") }
            ?.substringAfter("voltage:")?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
        val batteryTemperature = output.lineSequence()
            .firstOrNull { it.trim().startsWith("temperature:") }
            ?.substringAfter("temperature:")?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0

        val resolution = output.lineSequence()
            .firstOrNull { it.contains("Physical size:") }
            ?.substringAfter("Physical size:")?.trim() ?: ""
        val (w, h) = if (resolution.contains("x")) {
            resolution.split("x", limit = 2).mapNotNull { it.trim().toIntOrNull() }
        } else listOf(0, 0)

        val density = output.lineSequence()
            .firstOrNull { it.contains("Physical density:") }
            ?.substringAfter("Physical density:")?.trim()?.toIntOrNull() ?: 0

        val memTotal = output.lineSequence()
            .firstOrNull { it.contains("MemTotal:") }
            ?.substringAfter("MemTotal:")?.trim() ?: ""

        val memAvail = output.lineSequence()
            .firstOrNull { it.contains("MemAvailable:") }
            ?.substringAfter("MemAvailable:")?.trim() ?: ""

        val uptimeRaw = output.lineSequence()
            .firstOrNull { it.trim().startsWith("up time:") || it.trim().startsWith("Uptime:") }
            ?.substringAfter(":")?.trim() ?: ""

        return DeviceInfo(
            model = props["ro.product.model"] ?: "",
            brand = props["ro.product.brand"] ?: "",
            device = props["ro.product.device"] ?: "",
            androidVersion = props["ro.build.version.release"] ?: "",
            sdkVersion = props["ro.build.version.sdk"] ?: "",
            buildId = props["ro.build.display.id"] ?: "",
            fingerprint = props["ro.build.fingerprint"] ?: "",
            abi = props["ro.product.cpu.abi"] ?: "",
            serialno = props["ro.serialno"] ?: "",
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus,
            batteryHealth = batteryHealth,
            batteryTechnology = batteryTechnology,
            batteryDesignCapacity = batteryDesignCapacity,
            batteryCurrentCapacity = batteryCurrentCapacity,
            batteryVoltage = batteryVoltage,
            batteryTemperature = batteryTemperature,
            screenWidth = w,
            screenHeight = h,
            density = density,
            memTotal = memTotal,
            memAvail = memAvail,
            uptime = uptimeRaw
        )
    }

    /**
     * 用 pm list packages -s / -3 的结果做分类，同时利用安装路径辅助判断。
     * systemPkgs / thirdPartyPkgs 是从 pm list packages -s / -3 提取的包名集合。
     */
    fun parsePackageListWithFilter(
        output: String,
        systemPkgs: Set<String>,
        thirdPartyPkgs: Set<String>,
        disabledPkgs: Set<String> = emptySet()
    ): List<AppEntry> {
        return output.lines()
            .filter { it.startsWith("package:") }
            .map { line ->
                val content = line.removePrefix("package:")
                val pipeIdx = content.indexOf('|')
                val pathAndPkg = if (pipeIdx > 0) content.substring(0, pipeIdx) else content
                val versionName = if (pipeIdx > 0) content.substring(pipeIdx + 1) else ""

                val eqIdx = pathAndPkg.lastIndexOf('=')
                val path = if (eqIdx > 0) pathAndPkg.substring(0, eqIdx) else ""
                val pkgName = if (eqIdx > 0) pathAndPkg.substring(eqIdx + 1) else pathAndPkg

                // 综合判断：pm 标记 OR 系统分区路径，且排除第三方
                val isSystem = (pkgName in systemPkgs || isSystemPath(path)) && pkgName !in thirdPartyPkgs

                AppEntry(
                    packageName = pkgName,
                    versionName = versionName,
                    isSystem = isSystem,
                    isEnabled = pkgName !in disabledPkgs
                )
            }
            .sortedWith(compareBy<AppEntry> { it.isSystem }.thenBy { it.packageName })
    }

    /**
     * 根据安装路径判断是否位于系统分区。
     * 覆盖 /system、/vendor、/product、/odm、/apex、/system_ext 等常见系统路径。
     */
    private fun isSystemPath(path: String): Boolean {
        if (path.isEmpty()) return false
        val lower = path.lowercase()
        return lower.startsWith("/system/") ||
                lower.startsWith("/vendor/") ||
                lower.startsWith("/product/") ||
                lower.startsWith("/odm/") ||
                lower.startsWith("/apex/") ||
                lower.startsWith("/system_ext/")
    }

    fun parsePackageList(output: String): List<AppEntry> {
        return output.lines()
            .filter { it.startsWith("package:") }
            .map { line ->
                // 格式: package:/path/to.apk=com.example.app|versionName
                val content = line.removePrefix("package:")
                val pipeIdx = content.indexOf('|')
                val pathAndPkg = if (pipeIdx > 0) content.substring(0, pipeIdx) else content
                val versionName = if (pipeIdx > 0) content.substring(pipeIdx + 1) else ""

                val eqIdx = pathAndPkg.lastIndexOf('=')
                val path = if (eqIdx > 0) pathAndPkg.substring(0, eqIdx) else ""
                val pkgName = if (eqIdx > 0) pathAndPkg.substring(eqIdx + 1) else pathAndPkg

                // 路径法：不在 /data/ 下 = 系统应用
                val isSystem = path.isNotEmpty() && !path.startsWith("/data/")

                AppEntry(
                    packageName = pkgName,
                    versionName = versionName,
                    isSystem = isSystem
                )
            }
            .sortedWith(compareBy<AppEntry> { it.isSystem }.thenBy { it.packageName })
    }

    fun parseFileListing(output: String, basePath: String): List<FileEntry> {
        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex(), limit = 8)
                if (parts.size < 7) return@mapNotNull null
                val perms = parts[0]
                val isDir = perms.startsWith("d")
                val size = parts[4].toLongOrNull() ?: 0
                val date = "${parts[5]} ${parts[6]}"
                var name = parts.drop(7).joinToString(" ")
                if (name == "." || name == ".." || name.isBlank()) return@mapNotNull null
                // 符号链接：只取 -> 前面的文件名，忽略链接目标
                if (perms.startsWith("l")) {
                    val arrowIdx = name.indexOf(" -> ")
                    if (arrowIdx > 0) name = name.substring(0, arrowIdx)
                }
                FileEntry(
                    name = name,
                    path = if (basePath.endsWith("/")) "$basePath$name" else "$basePath/$name",
                    isDirectory = isDir,
                    size = size,
                    permissions = perms,
                    lastModified = date
                )
            }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
    }

    fun parseDiskUsage(output: String): String {
        return output.lines().drop(1).joinToString("\n") { it.trim() }
    }

    /**
     * 解析 df 输出，提取存储信息。
     * 优先匹配 /data，其次 /storage/emulated，最后取第一个有效分区。
     * 返回 Triple(totalBytes, usedBytes, freeBytes)
     */
    fun parseStorageInfo(dfOutput: String): Triple<Long, Long, Long> {
        val candidates = mutableListOf<Triple<Long, Long, Long>>()
        var dataPartition: Triple<Long, Long, Long>? = null

        for (line in dfOutput.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Filesystem") || trimmed.startsWith("tmpfs")) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size < 4) continue
            // 必须以 / 开头（真正的文件系统路径）
            if (!parts[0].startsWith("/")) continue

            val total = parseSize(parts[1])
            val used = parseSize(parts[2])
            val free = parseSize(parts[3])
            if (total <= 0) continue

            val entry = Triple(total, used, free)
            candidates.add(entry)

            // 优先匹配 /data 分区
            if (parts[0].contains("/data") || parts.size > 5 && parts[5].contains("/data")) {
                dataPartition = entry
            }
        }

        return dataPartition ?: candidates.firstOrNull() ?: Triple(0, 0, 0)
    }

    /**
     * 解析 df 中的尺寸字符串 (如 "128G", "64M", "1024K") 为字节。
     */
    private fun parseSize(s: String): Long {
        val trimmed = s.trim()
        val numPart = trimmed.dropLast(1).toDoubleOrNull() ?: return 0
        val multiplier = when (trimmed.lastOrNull()) {
            'K', 'k' -> 1024L
            'M', 'm' -> 1024L * 1024
            'G', 'g' -> 1024L * 1024 * 1024
            'T', 't' -> 1024L * 1024 * 1024 * 1024
            else -> trimmed.toLongOrNull() ?: 0
        }
        return (numPart * multiplier).toLong()
    }
}
