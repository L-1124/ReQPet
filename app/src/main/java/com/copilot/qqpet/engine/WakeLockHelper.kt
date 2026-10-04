package com.copilot.qqpet.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import com.copilot.qqpet.hook.HookLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 锁屏唤醒与防休眠冻结调度助手：
 * 1. 任务发包期间通过瞬态 WakeLock 保证 CPU 不休眠；
 * 2. 休眠等待期间通过 AlarmManager (RTC_WAKEUP) 准点唤醒 CPU，彻底解决锁屏协程假死问题。
 */
object WakeLockHelper {
    private const val TAG = "QQPetWakeLock"
    private const val ACTION_WAKEUP_ALARM = "com.tencent.mobileqq.action.PET_WAKE_TIMER"
    private const val WAKELOCK_TAG_PREFIX = "MobileQQ:NetFlow_"
    private const val REQUEST_CODE_ALARM = 10099

    @Volatile
    private var isReceiverRegistered = false

    @Volatile
    private var currentDeferred: CompletableDeferred<Unit>? = null

    private val wakeupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_WAKEUP_ALARM) {
                HookLog.log(TAG, "⏰ 收到系统定时唤醒广播，CPU 复苏，拉起巡检协程！")
                acquireTransientWakeLock(context, "AlarmSync", 8_000L)
                try {
                    currentDeferred?.complete(Unit)
                } catch (_: Throwable) {}
            }
        }
    }

    fun registerWakeupReceiver(context: Context) {
        if (isReceiverRegistered) return
        synchronized(this) {
            if (isReceiverRegistered) return
            try {
                val filter = IntentFilter(ACTION_WAKEUP_ALARM)
                val appContext = context.applicationContext ?: context
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(wakeupReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(wakeupReceiver, filter)
                }
                isReceiverRegistered = true
                HookLog.log(TAG, "WakeupReceiver 注册成功")
            } catch (t: Throwable) {
                HookLog.log(TAG, "注册 WakeupReceiver 异常: ${t.message}")
            }
        }
    }

    fun wakeUpImmediately() {
        try {
            currentDeferred?.complete(Unit)
        } catch (_: Throwable) {}
    }

    fun acquireTransientWakeLock(context: Context?, tag: String, timeoutMs: Long = 15_000L) {
        if (context == null) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lockTag = "$WAKELOCK_TAG_PREFIX$tag"
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockTag)
            lock.setReferenceCounted(false)
            lock.acquire(timeoutMs)
            HookLog.log(TAG, "已获取瞬态唤醒锁 ($lockTag, 限时 ${timeoutMs}ms)")
        } catch (t: Throwable) {
            HookLog.log(TAG, "获取唤醒锁失败: ${t.message}")
        }
    }

    /**
     * 在休眠期间结合 AlarmManager 硬件定时器与协程挂起，确保在锁屏/Doze 深度休眠时准点唤醒 CPU
     */
    suspend fun sleepWithAlarmWakeup(context: Context, durationMs: Long) {
        if (durationMs <= 0L) return
        registerWakeupReceiver(context)

        val appContext = context.applicationContext ?: context
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        val intent = Intent(ACTION_WAKEUP_ALARM).apply {
            setPackage(appContext.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(appContext, REQUEST_CODE_ALARM, intent, flags)

        val triggerAtMillis = System.currentTimeMillis() + durationMs
        var alarmScheduled = false

        // 仅对超过 3 分钟的长任务调度系统级硬件闹钟，短任务 (< 180s) 纯走瞬态锁与轻量挂起，避免被系统记录频繁 Exact Alarm 异常
        if (durationMs >= 180_000L) {
            try {
                if (alarmManager != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                    alarmScheduled = true
                }
            } catch (t: Throwable) {
                HookLog.log(TAG, "设置 AlarmManager 失败，回退纯协程等待: ${t.message}")
            }
        }

        val deferred = CompletableDeferred<Unit>()
        currentDeferred = deferred

        try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(durationMs) {
                    deferred.await()
                }
            }
        } finally {
            currentDeferred = null
            if (alarmScheduled) {
                try {
                    alarmManager?.cancel(pendingIntent)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 包装执行块，在发包与等待服务端响应期间持有瞬态唤醒锁，防止中途被系统冻结挂起
     */
    suspend fun <T> withExecutionWakeLock(context: Context, tag: String, timeoutMs: Long = 40_000L, block: suspend () -> T): T {
        val appContext = context.applicationContext ?: context
        var lock: PowerManager.WakeLock? = null
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val lockTag = "$WAKELOCK_TAG_PREFIX$tag"
            lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockTag)?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        } catch (_: Throwable) {}

        return try {
            block()
        } finally {
            try {
                if (lock?.isHeld == true) {
                    lock.release()
                }
            } catch (_: Throwable) {}
        }
    }
}
