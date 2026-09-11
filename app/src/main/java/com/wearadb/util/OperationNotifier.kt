package com.wearadb.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wearadb.R

/**
 * 设备侧长/短操作的状态栏通知封装。
 *
 * - 短操作（冻结/解冻/卸载/清数据/停止等）：发出「进行中 → 完成/失败」两条通知。
 * - 长操作（提取/安装）：带实时进度条，ongoing 期间可看到百分比进度。
 *
 * 通道固定 [NotificationManager.IMPORTANCE_LOW]（静默、不抢焦点）。
 * 若未授予 [android.Manifest.permission.POST_NOTIFICATIONS]（Android 13+），
 * 所有方法静默跳过——应用内 toast / 进度条仍照常工作，不会崩溃。
 *
 * 通知文案一律走 [R.string]，由系统按当前 locale 选取，满足多语言适配。
 */
class OperationNotifier(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_ops_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_ops_desc)
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(ch)
        }
    }

    /** 是否已具备发送通知的权限（Android 13+ 需 POST_NOTIFICATIONS） */
    fun canNotify(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    private fun baseBuilder(title: String, content: String?): NotificationCompat.Builder {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (content != null) setContentText(content) }
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    /** 进行中（不确定进度或带进度条），常驻直到 complete */
    fun start(id: Int, title: String, content: String?) {
        if (!canNotify()) return
        manager.notify(
            id,
            baseBuilder(title, content).setOngoing(true).setProgress(0, 0, true).build()
        )
    }

    /** 进度更新：total>0 时显示百分比，否则保持不确定态 */
    fun progress(id: Int, title: String, content: String?, written: Long, total: Long) {
        if (!canNotify()) return
        val b = baseBuilder(title, content).setOngoing(true)
        if (total > 0L) {
            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false)
        } else {
            b.setProgress(0, 0, true)
        }
        manager.notify(id, b.build())
    }

    /**
     * 终态：取消 ongoing、可点击清除。
     *
     * 先 [NotificationManager.cancel] 再 [NotificationManager.notify]——确保进行中的
     * ongoing 通知被非 ongoing 终态可靠覆盖。仅用同 ID 覆盖在部分 ROM 上 ongoing 标志不会翻转，
     * 表现为"通知卡在进行中不变"；先撤后发可规避该问题。
     */
    fun complete(id: Int, title: String, content: String?) {
        if (!canNotify()) return
        manager.cancel(id)
        manager.notify(
            id,
            baseBuilder(title, content).setOngoing(false).setAutoCancel(true).setProgress(0, 0, false).build()
        )
    }

    fun cancel(id: Int) = manager.cancel(id)

    companion object {
        const val CHANNEL_ID = "wearadb_ops"

        /** 由 key 生成稳定且非零的通知 id，保证同一操作的多次更新命中同一条通知 */
        fun idFor(key: String): Int {
            val h = key.hashCode().toLong() and 0x7FFFFFFF
            return if (h == 0L) 1 else h.toInt()
        }
    }
}
