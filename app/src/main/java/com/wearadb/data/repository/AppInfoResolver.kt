package com.wearadb.data.repository

import android.content.Context
import android.util.Log
import com.wearadb.log.WearAdbLogger
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.text.Charsets
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用名 / 图标的解析与缓存。
 *
 * 原理：设备端无 root 时无法直接读 resources.arsc，但可以用 `app_process` 免 root 起一个
 * Java 进程，通过反射 ActivityThread.systemMain() 拿到系统 Context，再用 PackageManager
 * 取应用名、用 createPackageContext + getDrawableForDensity 取图标，最后 Drawable -> PNG。
 *
 * dex 随 APK 打包在 assets/appinfo.dex，首次使用时释放到 filesDir 并 push 到设备。
 * 全部 I/O（执行命令 / 推送 / 拉取）由调用方通过回调注入，本类只负责流程与缓存。
 *
 * 输出协议（每行）：`包名\t应用名\t图标PNG路径`；名称失败为 `<ERR>`；图标失败时路径为空。
 */
class AppInfoResolver(private val appContext: Context) {

    companion object {
        private const val TAG = "AppInfoResolver"

        /** 设备端工作目录，所有临时产物都放这里，便于一次性清理。 */
        const val REMOTE_DIR = "/data/local/tmp/wearadb_appinfo"
        const val REMOTE_DEX = "$REMOTE_DIR/appinfo.dex"
        const val REMOTE_ICONS = "$REMOTE_DIR/icons"

        /** dex 版本号：dex 内容变更时必须递增，否则设备上会一直沿用旧的。 */
        private const val DEX_VERSION = 1
        private const val ASSET_DEX = "appinfo.dex"

        /** 图标目标边长（px）。列表项按 32dp 显示，96px 足够清晰且体积可控。 */
        const val ICON_SIZE = 96

        /**
         * 单次 app_process 启动约 2.8s 固定开销，必须整批传包名；
         * 但整批命令过长会触及 shell 参数长度上限（实测 395 包 ≈ 11KB 可用，
         * 手表端余量更小），故按 200 个/批切分——两批仍远快于逐包执行。
         */
        private const val BATCH_SIZE = 200
    }

    /** 解析结果：应用名 + 图标 PNG 字节。字段可为 null 表示该项解析失败。 */
    class AppInfo(
        val label: String?,
        val iconBytes: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AppInfo) return false
            return label == other.label && iconBytes.contentEquals(other.iconBytes)
        }

        override fun hashCode(): Int =
            (label?.hashCode() ?: 0) * 31 + (iconBytes?.contentHashCode() ?: 0)
    }

    /** 磁盘缓存目录：filesDir/appinfo_cache/<serial>/，按设备序列号隔离。 */
    private fun cacheDir(serial: String): File =
        File(File(appContext.filesDir, "appinfo_cache"), serial.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private val memCache = ConcurrentHashMap<String, AppInfo>()
    private val knownNoIcon = ConcurrentHashMap.newKeySet<String>()
    private val knownNoLabel = ConcurrentHashMap.newKeySet<String>()

    /** 设备端 tar 是否可用（探测一次后缓存）。缺失则全程回退逐图标 pull。 */
    private var tarProbed = false
    private var tarAvailable = true

    private fun key(serial: String, pkg: String) = "$serial|$pkg"

    /** 已知资源缺失、无需再试的包。 */
    fun isKnownMissing(serial: String, pkg: String, wantIcon: Boolean): Boolean {
        val k = key(serial, pkg)
        return if (wantIcon) knownNoIcon.contains(k) else knownNoLabel.contains(k)
    }

    /** 内存缓存中的名称（避免读盘）。 */
    fun memoryLabel(serial: String, pkg: String): String? = memCache[key(serial, pkg)]?.label

    /** 已缓存的图标文件（供 UI 直接加载 File）。 */
    fun cachedIconFile(serial: String, pkg: String): File? {
        val f = File(cacheDir(serial), "$pkg.png")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 已缓存的名称（内存优先，其次读盘）。 */
    fun cachedLabel(serial: String, pkg: String): String? {
        memCache[key(serial, pkg)]?.label?.let { return it }
        val f = File(cacheDir(serial), "$pkg.label")
        if (!f.exists()) return null
        return runCatching { f.readText().trim() }.getOrNull()?.ifEmpty { null }
    }

    /** 是否已有磁盘/内存缓存可用。 */
    fun hasCache(serial: String, pkg: String): Boolean =
        memCache.containsKey(key(serial, pkg)) ||
                File(cacheDir(serial), "$pkg.label").exists() ||
                File(cacheDir(serial), "$pkg.png").exists()

    /** 释放 assets 中的 dex 到本地文件，返回本地文件（已存在则直接返回）。 */
    fun ensureLocalDex(): File? {
        val local = File(File(appContext.filesDir, "appinfo"), "appinfo_v$DEX_VERSION.dex")
        if (local.exists() && local.length() > 0) return local
        return try {
            local.parentFile?.mkdirs()
            appContext.assets.open(ASSET_DEX).use { input ->
                local.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "ensureLocalDex: 释放 dex 到本地 ${local.length()} bytes")
            local
        } catch (t: Throwable) {
            WearAdbLogger.e(TAG, "ensureLocalDex: 释放 dex 失败: ${t.message}", t)
            null
        }
    }

    /** 读取磁盘缓存的名称（不做设备交互）。 */
    private fun readCachedLabel(serial: String, pkg: String): String? {
        memCache[key(serial, pkg)]?.label?.let { return it }
        val f = File(cacheDir(serial), "$pkg.label")
        if (!f.exists()) return null
        return runCatching { f.readText().trim() }.getOrNull()?.ifEmpty { null }
    }

    private fun readCachedIcon(serial: String, pkg: String): ByteArray? {
        memCache[key(serial, pkg)]?.iconBytes?.let { return it }
        val f = File(cacheDir(serial), "$pkg.png")
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    /**
     * 批量解析应用名 + 图标。
     *
     * @param packages 需要解析的包名（通常先过滤掉已缓存的）。
     * @param exec 执行设备命令，返回标准输出；null 表示通道不可用。
     * @param push 推送本地文件到设备，返回是否成功。
     * @param pull 拉取设备文件，返回字节；null 表示失败。
     * @param onProgress (已完成, 总数)
     * @return 本次新解析出的 包名 -> AppInfo
     */
    suspend fun resolve(
        serial: String,
        packages: List<String>,
        exec: suspend (String) -> String?,
        push: suspend (localPath: String, remotePath: String) -> Boolean,
        pull: suspend (remotePath: String) -> ByteArray?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, AppInfo> {
        if (packages.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, AppInfo>()
        cacheDir(serial).mkdirs()

        // 探测设备端 tar 是否可用（toybox 普遍有 /system/bin/tar）；缺失则全程回退逐图标 pull
        if (!tarProbed) {
            tarAvailable = exec("command -v tar >/dev/null 2>&1 && echo yes")?.contains("yes") == true
            tarProbed = true
            Log.d(TAG, "resolve: tarAvailable=$tarAvailable")
        }

        // 1. 分批执行
        val total = packages.size
        var done = 0
        for (batch in packages.chunked(BATCH_SIZE)) {
            // 1.1 部署 dex
            if (!deployDex(exec, push)) {
                WearAdbLogger.w(TAG, "resolve: dex 部署失败，中止剩余 ${total - done} 个包")
                break
            }

            // 1.2 清理设备端旧图标，避免残留旧文件被误读
            exec("rm -rf $REMOTE_ICONS")

            // 1.3 批量取名称 + 生成图标
            val quoted = batch.joinToString(" ") { shQuote(it) }
            val cmd = "CLASSPATH=$REMOTE_DEX app_process /system/bin AppInfoProbe " +
                    "$REMOTE_ICONS -s $ICON_SIZE $quoted"
            val output = exec(cmd)
            if (output == null) {
                WearAdbLogger.w(TAG, "resolve: 批量执行失败，中止剩余 ${total - done} 个包")
                break
            }

            // 1.4 解析行式输出
            val labels = HashMap<String, String?>()
            val iconPaths = ArrayList<Pair<String, String>>()
            for (line in output.lines()) {
                if (line.isBlank() || line.startsWith("FATAL") || line.startsWith("USAGE")) continue
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val pkg = parts[0].trim()
                if (pkg.isEmpty()) continue
                val raw = parts[1].trim()
                labels[pkg] = if (raw.isEmpty() || raw == "<ERR>") null else raw
                val iconPath = parts[2].trim()
                if (iconPath.isNotEmpty()) iconPaths.add(pkg to iconPath)
            }

            if (labels.isEmpty()) {
                WearAdbLogger.w(TAG, "resolve: 本批无有效输出（${batch.size} 个包）")
                done += batch.size
                onProgress(done, total)
                continue
            }

            // 1.5 写名称缓存
            val dir = cacheDir(serial)
            for ((pkg, label) in labels) {
                if (label != null) {
                    runCatching { File(dir, "$pkg.label").writeText(label) }
                } else {
                    knownNoLabel.add(key(serial, pkg))
                }
            }

            // 1.6 拉图标：设备端 tar 成单个 tar.gz → 一次 pull → 手表侧解包。
            // 规避逐图标 600 次 SYNC 往返（Windows-adb 实测单次 pull 0.08s vs 逐文件 5.6s，12 倍差距）。
            val tarPath = "$REMOTE_DIR/icons.tar.gz"
            val iconData = if (tarAvailable) {
                exec("tar -czf $tarPath -C $REMOTE_ICONS . 2>/dev/null")
                pull(tarPath)
            } else null

            val extracted = if (iconData != null && iconData.isNotEmpty()) {
                extractTarGz(iconData, dir)
            } else {
                emptySet()
            }

            if (extracted.isEmpty() && iconPaths.isNotEmpty()) {
                // tar 不可用或解包失败 → 回退逐图标 pull（保持原行为，保证正确性）
                WearAdbLogger.w(TAG, "resolve: tar 路径不可用时回退逐图标 pull（${iconPaths.size} 个）")
                for ((pkg, remote) in iconPaths) {
                    val bytes = pull(remote)
                    if (bytes != null && bytes.isNotEmpty()) {
                        runCatching { File(dir, "$pkg.png").writeBytes(bytes) }
                    } else {
                        knownNoIcon.add(key(serial, pkg))
                    }
                }
            } else {
                // 标记未成功解出的包为无图标，避免后续重复重试
                for ((pkg, _) in iconPaths) {
                    if (!extracted.contains(pkg)) knownNoIcon.add(key(serial, pkg))
                }
            }

            // 1.7 组装内存缓存与返回值
            for (pkg in batch) {
                val info = AppInfo(labels[pkg], readCachedIcon(serial, pkg))
                memCache[key(serial, pkg)] = info
                out[pkg] = info
            }

            done += batch.size
            onProgress(done, total)
        }

        // 2. 清理设备端图标目录与 tar 包（dex 保留复用）
        exec("rm -rf $REMOTE_ICONS")
        exec("rm -f $REMOTE_DIR/icons.tar.gz")
        Log.d(TAG, "resolve: 完成，resolved=${out.size}/$total, serial=$serial")
        return out
    }

    /** 释放 assets dex → 本地 → push 设备；设备端已存在且大小一致则跳过。 */
    private suspend fun deployDex(
        exec: suspend (String) -> String?,
        push: suspend (localPath: String, remotePath: String) -> Boolean
    ): Boolean {
        val local = ensureLocalDex() ?: return false
        val localSize = local.length()

        if (remoteFileSize(exec) == localSize) return true

        exec("mkdir -p $REMOTE_DIR")
        if (!push(local.absolutePath, REMOTE_DEX)) {
            WearAdbLogger.w(TAG, "deployDex: push 失败")
            return false
        }
        val verify = remoteFileSize(exec)
        if (verify != localSize) {
            WearAdbLogger.w(TAG, "deployDex: 大小校验失败 local=$localSize remote=$verify")
            return false
        }
        Log.d(TAG, "deployDex: push 完成并校验通过（$localSize bytes）")
        return true
    }

    /**
     * 读设备端 dex 大小；不存在或读取失败返回 -1。
     * 注意：文件不存在时 shell 输出为空白行而非空串，必须用 toLongOrNull 兜底。
     */
    private suspend fun remoteFileSize(exec: suspend (String) -> String?): Long {
        val out = exec("stat -c %s $REMOTE_DEX 2>/dev/null") ?: return -1L
        return out.trim().toLongOrNull() ?: -1L
    }

    /**
     * 解压设备端 tar.gz 图标包到 outDir/<pkg>.png。
     * 设备用 toybox tar 打的包为标准 ustar：512 字节块，文件头偏移 0=名称(100)、124=大小(12,八进制)、156=类型标志。
     * 返回成功写出的包名集合（不含 .png 后缀），供调用方标记 knownNoIcon。
     * 仅依赖 java.util.zip.GZIPInputStream（Android 内置），不引入新依赖。
     */
    private fun extractTarGz(data: ByteArray, outDir: File): Set<String> {
        val result = LinkedHashSet<String>()
        outDir.mkdirs()
        try {
            val bis = BufferedInputStream(GZIPInputStream(ByteArrayInputStream(data)))
            val header = ByteArray(512)
            while (true) {
                if (bis.read(header) != 512) break
                // 结束标志：全零块（通常连续两个）
                if (header[0] == 0.toByte() && header.all { it == 0.toByte() }) {
                    bis.read(ByteArray(512))
                    break
                }
                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000').trim()
                val typeFlag = header[156].toInt().toChar()
                val sizeStr = String(header, 124, 12, Charsets.UTF_8).trimEnd('\u0000', ' ').trim()
                val size = if (sizeStr.isEmpty()) 0L else runCatching { sizeStr.toLong(8) }.getOrDefault(0L)
                // 目录/特殊文件条目：跳过其数据块
                if (typeFlag != '0' && typeFlag != '\u0000') {
                    bis.skip(((size + 511) / 512) * 512L)
                    continue
                }
                // 仅处理 .png 文件（tar 用 -C icons . 打包，文件名形如 ./<pkg>.png）
                val base = name.removePrefix("./").substringAfterLast('/')
                if (!base.endsWith(".png")) {
                    bis.skip(((size + 511) / 512) * 512L)
                    continue
                }
                val pkg = base.removeSuffix(".png")
                val nBlocks = ((size + 511) / 512).toInt()
                val buf = ByteArray(nBlocks * 512)
                var got = 0
                while (got < buf.size) {
                    val r = bis.read(buf, got, buf.size - got)
                    if (r < 0) break
                    got += r
                }
                val fileBytes = buf.copyOfRange(0, size.toInt().coerceAtMost(buf.size))
                if (fileBytes.isNotEmpty()) {
                    runCatching { File(outDir, "$pkg.png").writeBytes(fileBytes) }
                    result.add(pkg)
                }
            }
        } catch (t: Throwable) {
            WearAdbLogger.w(TAG, "extractTarGz: 解包失败: ${t.message}")
        }
        return result
    }

    /** 简单 shell 单引号转义。 */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 连接断开或切换设备时清内存缓存（磁盘缓存按 serial 隔离，保留）。 */
    fun clearMemory() {
        memCache.clear()
        knownNoIcon.clear()
        knownNoLabel.clear()
    }

    /** 清空指定设备的磁盘缓存（用于「强制刷新」）。 */
    fun clearDisk(serial: String) {
        clearMemory()
        runCatching { cacheDir(serial).deleteRecursively() }
    }
}
