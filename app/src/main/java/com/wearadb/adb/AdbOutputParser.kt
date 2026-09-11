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
            // 0. dumpsys batterystats: "Last learned battery capacity: 4524 mAh"（免 root，当前满容量学习值）
            //    注意：勿用 "Estimated battery capacity"（可能是学习初期的极小值，如 4.00 mAh）
            val learned = output.lineSequence()
                .firstOrNull { it.trim().startsWith("Last learned battery capacity:") }
                ?.substringAfter(":")?.substringBefore("mAh")?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            if (learned != null && learned > 0) return@run learned
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

        // 电池健康度（容量百分比）= 当前满容量 / 设计容量。
        // 设计容量多数机型仅 root 可读（/sys/.../charge_full_design，shell 身份 Permission denied），
        // 故真正的健康度在采集层（AdbRepository / UsbAdbRepository）拿到 root 容量后合并时计算。
        // 此处先置 0，避免用不完整的非 root 数据给出误导值。
        val batteryHealthPct = 0f

        // 芯片平台（免 root，getprop 已包含）
        val chipPlatform = props["ro.board.platform"] ?: ""

        // IMEI：从 getprop 多属性兜底（免 root；部分 ROM 对 shell 暴露 persist.radio.imei）。
        // 过滤规则：纯数字且长度 ≥ 10，去重。双卡 → "SIM1: x\nSIM2: y"。
        val imei = run {
            val candidates = listOf(
                "persist.radio.imei", "persist.radio.imei2",
                "ro.ril.oem.imei", "persist.vendor.radio.imei"
            ).mapNotNull { props[it]?.takeIf { v -> v.length >= 10 && v.all { c -> c.isDigit() } } }
                .distinct()
            when (candidates.size) {
                0 -> ""
                1 -> candidates[0]
                else -> candidates.mapIndexed { i, v -> "SIM${i + 1}: $v" }.joinToString("\n")
            }
        }

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
            batteryHealthPct = batteryHealthPct,
            chipPlatform = chipPlatform,
            imei = imei,
            screenWidth = w,
            screenHeight = h,
            density = density,
            memTotal = memTotal,
            memAvail = memAvail,
            uptime = uptimeRaw
        )
    }

    /**
     * 解析 root 专属数据的复合命令输出。
     *
     * 该命令以 `echo ==EXTRA==;` 打头（让 cleanShellOutput 能开始收集），
     * 随后 `su -c '...'` 内用固定锚点分隔：
     *   CYCLE          → 下一行起为循环次数（直到 UFS 锚点）
     *   UFS            → 下一行为 health_descriptor 目录（可能为空），再下一行为寿命 A，
     *                    之后 SEP，再之后为寿命 B
     *
     * 若输出中不含 CYCLE 锚点，说明 su 不可用 / 未授权 → 返回 null（表示无 root）。
     */
    fun parseRootExtras(text: String): RootExtras? {
        val idx = text.indexOf("==EXTRA==")
        if (idx < 0) return null
        val body = text.substring(idx)
        if (!body.contains("CYCLE")) return null

        val lines = body.lines()
        var cycleCount = 0
        var flashA = -1
        var flashB = -1
        var chargeFull = 0L        // 当前满容量 µAh，0 = 未知
        var chargeFullDesign = 0L  // 设计容量 µAh，0 = 未知

        for (i in lines.indices) {
            when (lines[i].trim()) {
                "CYCLE" -> {
                    val v = lines.getOrNull(i + 1)?.trim() ?: ""
                    cycleCount = v.toIntOrNull() ?: 0
                }
                "UFS" -> {
                    // i+1 = 目录（可能空），i+2 = 寿命 A，i+3 = "SEP"，i+4 = 寿命 B
                    val a = lines.getOrNull(i + 2)?.trim() ?: ""
                    val b = lines.getOrNull(i + 4)?.trim() ?: ""
                    flashA = parseUfsLife(a)
                    flashB = parseUfsLife(b)
                }
                "CFULL" -> {
                    val v = lines.getOrNull(i + 1)?.trim() ?: ""
                    chargeFull = v.toLongOrNull() ?: 0L
                }
                "CDESIGN" -> {
                    val v = lines.getOrNull(i + 1)?.trim() ?: ""
                    chargeFullDesign = v.toLongOrNull() ?: 0L
                }
            }
        }
        return RootExtras(
            cycleCount = cycleCount, flashLifeA = flashA, flashLifeB = flashB,
            chargeFull = chargeFull, chargeFullDesign = chargeFullDesign
        )
    }

    /**
     * 解析 UFS life_time_estimation 字段。
     * 取值形如 0x01~0x0B（值越小寿命越充足）：剩余% = (0x0B - 值) × 10，钳制到 0~100。
     * 非 0x 前缀或解析失败返回 -1（未知）。
     */
    private fun parseUfsLife(value: String): Int {
        val v = value.trim()
        if (!v.startsWith("0x", ignoreCase = true)) return -1
        val hex = v.substring(2).toIntOrNull(16) ?: return -1
        return ((0x0B - hex) * 10).coerceIn(0, 100)
    }

    /**
     * root 专属数据解析结果。字段为 -1 / 0 表示未知。
     */
    data class RootExtras(
        val cycleCount: Int = 0,
        val flashLifeA: Int = -1,
        val flashLifeB: Int = -1,
        val chargeFull: Long = 0,        // 当前满容量 µAh，0 = 未知（仅 root 可读）
        val chargeFullDesign: Long = 0   // 设计容量 µAh，0 = 未知（仅 root 可读）
    )

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
                    if (arrowIdx > 0) {
                        name = name.substring(0, arrowIdx)
                    } else if (name.startsWith("->")) {
                        // ls -L 对悬空符号链接（stat 失败）会把大小/日期列打成 "?" 占位，
                        // 列位整体前移，解析后名字只剩 "-> 目标"，真实文件名在 parts[6]。
                        // 此前会产出形如 "/-> ?" 的重复 path，LazyColumn 重复 key 直接崩溃。
                        val recovered = parts.getOrNull(6)?.trim().orEmpty()
                        if (recovered.isBlank() || recovered == "->" || recovered.contains(" -> ")) {
                            return@mapNotNull null
                        }
                        name = recovered
                    }
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
            // 兜底去重：畸形行（如悬空链接占位列）可能解析出相同 path，
            // LazyColumn 按 path 作 key，重复即崩，这里保证唯一。
            .distinctBy { it.path }
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
