package com.wearadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * 小米 HyperOS 公平运行内存机制适配。
 * 监听系统内存预警/查杀广播，及时释放资源并回调系统。
 */
class MemoryReceiver : IBinder.DeathRecipient {

    companion object {
        private const val TAG = "MemoryReceiver"
        private const val ITGSA_ACTION = "itgsa.intent.action.TRIM"
        const val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION

        // notifyType
        const val NOTIFY_TYPE_PHYSICAL = 1000  // 物理内存异常
        const val NOTIFY_TYPE_JAVA_HEAP = 2000  // Java 堆内存异常

        // result
        const val RESULT_BACKUP_COMPLETE = 0  // 数据保存完成
        const val RESULT_RELEASE_COMPLETE = 1  // 资源释放完成

        @Volatile
        private var INSTANCE: MemoryReceiver? = null

        fun getInstance(): MemoryReceiver {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryReceiver().also { INSTANCE = it }
            }
        }
    }

    private var mRemote: IBinder? = null
    private var mInitialized = false
    private var mHandler: Handler? = null

    override fun binderDied() {
        synchronized(this) {
            mRemote?.let {
                try { it.unlinkToDeath(this, 0) } catch (_: Exception) {}
            }
            mRemote = null
        }
    }

    /**
     * 在 Application.onCreate() 中调用，注册广播接收器。
     */
    fun initialize(context: Context) {
        synchronized(this) {
            if (mInitialized) return

            val ht = HandlerThread(TAG)
            ht.start()
            mHandler = Handler(ht.looper)

            val filter = IntentFilter(ITGSA_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(mReceiver, filter, null, mHandler)
            }

            mInitialized = true
            Log.i(TAG, "HyperOS memory receiver initialized")
        }
    }

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ITGSA_ACTION != intent.action) return

            val data = intent.extras ?: return
            val bundle = data.getBundle("common") ?: return

            val notifyType = bundle.getInt("notifyType")
            val notifyId = bundle.getInt("notifyId")
            val reason = bundle.getString("reason") ?: ""
            val callbackBinder = bundle.getBinder("callback")

            val extraData = data.getBundle("extra")
            val pss = extraData?.getInt("pss") ?: 0
            val pssLimit = extraData?.getInt("pssLimit") ?: 0
            val heapAlloc = extraData?.getInt("heapAlloc") ?: 0
            val heapCapacity = extraData?.getInt("heapCapacity") ?: 0

            Log.w(TAG, "Memory warning: type=$notifyType, id=$notifyId, reason=$reason, " +
                    "pss=${pss}KB/${pssLimit}KB, heap=${heapAlloc}KB/${heapCapacity}KB")

            if (callbackBinder != null) {
                handleReceived(notifyType, notifyId, callbackBinder)
            } else {
                Log.w(TAG, "Callback binder is null")
            }
        }
    }

    private fun handleReceived(notifyType: Int, notifyId: Int, callback: IBinder) {
        if (!checkRemote(callback)) return

        // 释放缓存等资源
        releaseMemory(notifyType)

        // 3秒内回调系统
        val result = if (notifyType == NOTIFY_TYPE_PHYSICAL) {
            RESULT_RELEASE_COMPLETE
        } else {
            RESULT_BACKUP_COMPLETE
        }
        reply(notifyType, notifyId, result, null)
    }

    private fun checkRemote(callback: IBinder): Boolean {
        synchronized(this) {
            if (mRemote == null) {
                try {
                    mRemote = callback
                    callback.linkToDeath(this, 0)
                } catch (e: Exception) {
                    mRemote = null
                    Log.e(TAG, "Failed to link callback binder", e)
                    return false
                }
            }
        }
        return true
    }

    /**
     * 释放内存资源。根据 notifyType 决定释放策略。
     */
    private fun releaseMemory(notifyType: Int) {
        try {
            // 清理运行时缓存
            System.gc()

            when (notifyType) {
                NOTIFY_TYPE_PHYSICAL -> {
                    Log.w(TAG, "Physical memory warning - releasing resources")
                    // 物理内存异常：积极释放
                }
                NOTIFY_TYPE_JAVA_HEAP -> {
                    Log.w(TAG, "Java heap memory warning - releasing resources")
                    // Java 堆异常：释放大对象
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing memory", e)
        }
    }

    private fun reply(notifyType: Int, notifyId: Int, result: Int, extra: Bundle?) {
        synchronized(this) {
            val remote = mRemote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInt(notifyType)
                data.writeInt(notifyId)
                data.writeInt(result)
                data.writeBundle(extra ?: Bundle())
                remote.transact(TRANSACTION_EXCEPTION_REPLY, data, reply, IBinder.FLAG_ONEWAY)
                reply.readException()
                Log.i(TAG, "Reply sent: type=$notifyType, id=$notifyId, result=$result")
            } catch (e: Exception) {
                Log.e(TAG, "Reply failed", e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
