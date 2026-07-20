package com.wearadb.log

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 全局文件日志工具类。
 * 日志写入 /sdcard/wearadb/logs/ 目录，用户可通过文件管理器查看和分享。
 */
object WearAdbLogger {

    private var logDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val executor = Executors.newSingleThreadExecutor()
    private const val MAX_LOG_DAYS = 7

    fun init(context: Context) {
        logDir = File(Environment.getExternalStorageDirectory(), "wearadb/logs").also {
            it.mkdirs()
        }
        cleanOldLogs()
        i("WearAdbLogger", "日志系统初始化完成, 目录=${logDir?.absolutePath}")
    }

    fun i(tag: String, msg: String) = log("INFO", tag, msg)

    fun w(tag: String, msg: String) = log("WARN", tag, msg)

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        log("ERROR", tag, fullMsg)
    }

    private fun log(level: String, tag: String, msg: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $msg"
        Log.d(tag, msg)
        val dir = logDir ?: return
        executor.execute {
            try {
                val file = File(dir, "wearadb_${dateFormat.format(Date())}.log")
                FileWriter(file, true).use { writer ->
                    writer.appendLine(line)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanOldLogs() {
        val dir = logDir ?: return
        try {
            val cutoff = System.currentTimeMillis() - MAX_LOG_DAYS * 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("wearadb_") && file.name.endsWith(".log")) {
                    if (file.lastModified() < cutoff) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun getLogDir(): File? = logDir
}
