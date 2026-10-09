package io.github.reqpet.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import io.github.reqpet.HookEntry
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.hook.HookLog
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

internal object SettingsDialogController {

    private const val TAG = "SettingsDialog"
    private const val KEY_SETTINGS_DIALOG = "io.github.reqpet.settings_dialog"
    private const val KEY_COMPOSE_STATE = "compose_state"
    private const val MSG_OPEN_FAILED = "当前页面无法打开设置，请返回 QQ 设置后重试"

    private enum class ActivityStage {
        CREATED,
        STARTED,
        RESUMED,
        PAUSED,
        STOPPED,
        DESTROYED
    }

    private class ActivityRecord(
        val activity: Activity,
        var stage: ActivityStage = ActivityStage.CREATED,
        var isOpenRequested: Boolean = false,
        var dialog: QQSettingDialog? = null,
        var pendingRestoreBundle: Bundle? = null,
        var showRequestTimestampMs: Long = 0L,
        var pendingAttachListener: View.OnAttachStateChangeListener? = null,
        var pendingAttachView: WeakReference<View>? = null
    )

    private val records = IdentityHashMap<Activity, ActivityRecord>()
    private var recentResumedActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var isInstalled = false
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (t: Throwable) {
            HookLog.e(TAG, "mainHandler lazy init error", t)
            null
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        try {
            val looper = try {
                Looper.getMainLooper()
            } catch (t: Throwable) {
                HookLog.e(TAG, "getMainLooper failed", t)
                null
            } ?: run {
                HookLog.trace(TAG, "runOnMainThread failed closed: main Looper unavailable")
                return
            }

            val myLooper = try {
                Looper.myLooper()
            } catch (t: Throwable) {
                null
            }

            if (myLooper != null && myLooper == looper) {
                try {
                    action()
                } catch (t: Throwable) {
                    HookLog.e(TAG, "Exception during main thread execution", t)
                }
            } else {
                val handler = mainHandler ?: run {
                    HookLog.trace(TAG, "runOnMainThread failed closed: mainHandler unavailable")
                    return
                }
                handler.post {
                    try {
                        action()
                    } catch (t: Throwable) {
                        HookLog.e(TAG, "Unhandled exception in mainHandler action", t)
                    }
                }
            }
        } catch (t: Throwable) {
            HookLog.e(TAG, "runOnMainThread unexpected error", t)
        }
    }

    fun install(context: Context) {
        runOnMainThread {
            try {
                installOnMainThread(context)
            } catch (t: Throwable) {
                HookLog.e(TAG, "install unexpected error", t)
            }
        }
    }

    private fun installOnMainThread(context: Context) {
        if (isInstalled) return
        try {
            val app = when (context) {
                is Application -> context
                else -> context.applicationContext as? Application
            }
            if (app == null) {
                HookLog.e(TAG, "install failed: application context is not Application ($context)")
                return
            }
            val callbacks = ControllerLifecycleCallbacks()
            app.registerActivityLifecycleCallbacks(callbacks)
            lifecycleCallbacks = callbacks
            isInstalled = true
            HookLog.log(TAG, "ActivityLifecycleCallbacks installed successfully")
        } catch (t: Throwable) {
            HookLog.e(TAG, "install failed: registerActivityLifecycleCallbacks threw exception", t)
        }
    }

    fun show(context: Context) {
        runOnMainThread {
            SettingsTrace.trace("settings.controller_show") {
                try {
                    showOnMainThread(context)
                } catch (t: Throwable) {
                    HookLog.e(TAG, "show failed with unexpected exception", t)
                    showToast(context, MSG_OPEN_FAILED)
                }
            }
        }
    }

    private fun showOnMainThread(context: Context) {
        if (!isInstalled) {
            HookLog.trace(TAG, "show rejected: lifecycle callbacks not installed")
            showToast(context, MSG_OPEN_FAILED)
            return
        }

        val owner = resolveOwnerActivity(context)
        if (owner == null || owner.isFinishing || owner.isDestroyed) {
            HookLog.trace(TAG, "show rejected: no live activity")
            showToast(context, MSG_OPEN_FAILED)
            return
        }

        val record = getOrCreateRecord(owner)
        record.isOpenRequested = true
        if (record.showRequestTimestampMs <= 0L) {
            record.showRequestTimestampMs = RuntimeDiagnostics.nowMs()
        }
        val existingDialog = record.dialog
        if (existingDialog != null) {
            if (existingDialog.isShowing) {
                return
            }
            cleanupRecordDialog(record)
            record.isOpenRequested = true
            record.showRequestTimestampMs = RuntimeDiagnostics.nowMs()
        }

        if (record.stage == ActivityStage.RESUMED) {
            requestDisplayOrDefer(record, owner)
        } else {
            HookLog.trace(TAG, "show requested while activity in stage ${record.stage}; keeping pending for resume")
        }
    }

    private fun resolveOwnerActivity(context: Context): Activity? {
        val activity = findActivity(context)
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            return activity
        }

        val fallback = recentResumedActivityRef?.get()
        if (fallback != null &&
            !fallback.isFinishing &&
            !fallback.isDestroyed &&
            records[fallback]?.stage == ActivityStage.RESUMED &&
            fallback.packageName == HookEntry.TARGET_PACKAGE &&
            fallback.window?.decorView?.windowToken != null
        ) {
            return fallback
        }

        return null
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        while (current is ContextWrapper) {
            if (current is Activity) return current
            if (!visited.add(current)) {
                HookLog.trace(TAG, "ContextWrapper cycle detected")
                break
            }
            current = current.baseContext
        }
        if (current is Activity) return current
        return null
    }

    private fun removePendingAttachListener(record: ActivityRecord) {
        val listener = record.pendingAttachListener ?: return
        record.pendingAttachListener = null
        try {
            val view = record.pendingAttachView?.get()
            view?.removeOnAttachStateChangeListener(listener)
        } catch (t: Throwable) {
            HookLog.e(TAG, "Failed to remove decorView attach listener", t)
        } finally {
            record.pendingAttachView = null
        }
    }

    private fun requestDisplayOrDefer(record: ActivityRecord, activity: Activity) {
        if (records[activity] !== record || !record.isOpenRequested ||
            record.stage != ActivityStage.RESUMED || record.dialog != null
        ) return
        if (activity.isFinishing || activity.isDestroyed) {
            cleanupRecordDialog(record)
            return
        }

        val decorView = activity.window?.decorView
        if (decorView == null) {
            cleanupRecordDialog(record)
            showToast(activity, MSG_OPEN_FAILED)
            return
        }
        if (decorView.windowToken != null) {
            removePendingAttachListener(record)
            createAndShowDialog(record, activity)
            return
        }
        if (record.pendingAttachListener != null && record.pendingAttachView?.get() === decorView) return

        removePendingAttachListener(record)
        if (record.showRequestTimestampMs <= 0L) {
            record.showRequestTimestampMs = RuntimeDiagnostics.nowMs()
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                try {
                    if (records[activity] !== record || record.pendingAttachListener !== this ||
                        record.pendingAttachView?.get() !== v
                    ) return
                    removePendingAttachListener(record)
                    if (!record.isOpenRequested || record.stage != ActivityStage.RESUMED ||
                        record.dialog != null
                    ) return
                    if (activity.isFinishing || activity.isDestroyed) {
                        cleanupRecordDialog(record)
                        return
                    }
                    if (v.windowToken == null) {
                        HookLog.trace(TAG, "display deferred: attached decorView has no windowToken")
                        return
                    }
                    createAndShowDialog(record, activity)
                } catch (t: Throwable) {
                    HookLog.e(TAG, "decorView attach callback failed", t)
                    cleanupRecordDialog(record)
                    showToast(activity, MSG_OPEN_FAILED)
                }
            }

            override fun onViewDetachedFromWindow(v: View) {}
        }
        record.pendingAttachListener = listener
        record.pendingAttachView = WeakReference(decorView)
        try {
            decorView.addOnAttachStateChangeListener(listener)
            HookLog.trace(TAG, "display deferred: waiting for decorView attach")
            if (decorView.windowToken != null) listener.onViewAttachedToWindow(decorView)
        } catch (t: Throwable) {
            HookLog.e(TAG, "decorView attach registration failed", t)
            cleanupRecordDialog(record)
            showToast(activity, MSG_OPEN_FAILED)
        }
    }

    private fun createAndShowDialog(record: ActivityRecord, activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            HookLog.trace(TAG, "createAndShowDialog rejected: activity finishing or destroyed")
            cleanupRecordDialog(record)
            return
        }

        val decorToken = activity.window?.decorView?.windowToken
        if (decorToken == null) {
            HookLog.trace(TAG, "display deferred: decorView windowToken unavailable before creation")
            return
        }
        val restoreBundle = record.pendingRestoreBundle
        val showRequestTimestampMs = if (record.showRequestTimestampMs > 0L) {
            record.showRequestTimestampMs
        } else {
            RuntimeDiagnostics.nowMs()
        }
        val openToDrawCookie = SettingsTrace.nextAsyncCookie()
        val onClosed: () -> Unit = {
            record.isOpenRequested = false
            record.dialog = null
            record.pendingRestoreBundle = null
            record.showRequestTimestampMs = 0L
            removePendingAttachListener(record)
        }

        val dialog: QQSettingDialog = try {
            QQSettingDialog(activity, restoreBundle, onClosed, showRequestTimestampMs, openToDrawCookie)
        } catch (t: Throwable) {
            if (restoreBundle != null) {
                HookLog.trace(TAG, "restore rejected: opening home")
                try {
                    QQSettingDialog(activity, null, onClosed, showRequestTimestampMs, openToDrawCookie)
                } catch (retryThrowable: Throwable) {
                    HookLog.e(TAG, "HOME retry creation also failed", retryThrowable)
                    cleanupRecordDialog(record)
                    showToast(activity, MSG_OPEN_FAILED)
                    return
                }
            } else {
                HookLog.e(TAG, "QQSettingDialog creation failed", t)
                cleanupRecordDialog(record)
                showToast(activity, MSG_OPEN_FAILED)
                return
            }
        }
        record.pendingRestoreBundle = null
        record.showRequestTimestampMs = 0L
        if (activity.isFinishing || activity.isDestroyed || activity.window?.decorView?.windowToken == null) {
            HookLog.trace(TAG, "createAndShowDialog rejected before show(): activity invalid or token lost")
            try {
                dialog.dismiss()
            } catch (_: Throwable) {
            }
            cleanupRecordDialog(record)
            showToast(activity, MSG_OPEN_FAILED)
            return
        }

        try {
            record.dialog = dialog
            dialog.show()
            dialog.onOwnerStarted()
            dialog.onOwnerResumed()
        } catch (t: Throwable) {
            HookLog.e(TAG, "dialog.show() failed: token or window failure", t)
            cleanupRecordDialog(record)
            showToast(activity, MSG_OPEN_FAILED)
        }
    }

    private fun cleanupRecordDialog(record: ActivityRecord) {
        removePendingAttachListener(record)
        try {
            record.dialog?.dismiss()
        } catch (t: Throwable) {
            HookLog.e(TAG, "cleanupRecordDialog dismiss error", t)
        }
        record.dialog = null
        record.isOpenRequested = false
        record.pendingRestoreBundle = null
        record.showRequestTimestampMs = 0L
    }

    private fun getOrCreateRecord(activity: Activity): ActivityRecord {
        return records.getOrPut(activity) { ActivityRecord(activity) }
    }

    private fun showToast(context: Context, message: String) {
        runOnMainThread {
            try {
                val appCtx = context.applicationContext ?: context
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                HookLog.e(TAG, "Failed to show toast: $message", t)
            }
        }
    }

    private class ControllerLifecycleCallbacks : Application.ActivityLifecycleCallbacks {

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            try {
                val record = getOrCreateRecord(activity)
                record.stage = ActivityStage.CREATED

                if (savedInstanceState != null && savedInstanceState.containsKey(KEY_SETTINGS_DIALOG)) {
                    record.isOpenRequested = true
                    try {
                        val dialogBundle = savedInstanceState.getBundle(KEY_SETTINGS_DIALOG)
                        if (dialogBundle != null) {
                            dialogBundle.classLoader = SettingsDialogController::class.java.classLoader
                            val composeState = dialogBundle.getBundle(KEY_COMPOSE_STATE)
                            composeState?.classLoader = SettingsDialogController::class.java.classLoader
                            record.pendingRestoreBundle = composeState
                        } else {
                            record.pendingRestoreBundle = null
                        }
                    } catch (t: Throwable) {
                        HookLog.trace(TAG, "restore rejected: opening home")
                        record.pendingRestoreBundle = null
                    }
                }
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivityCreated error", t)
                val record = records[activity]
                if (record != null) {
                    cleanupRecordDialog(record)
                }
            }
        }

        override fun onActivityStarted(activity: Activity) {
            try {
                val record = getOrCreateRecord(activity)
                record.stage = ActivityStage.STARTED
                record.dialog?.onOwnerStarted()
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivityStarted error", t)
                val record = records[activity]
                if (record != null) {
                    cleanupRecordDialog(record)
                }
            }
        }

        override fun onActivityResumed(activity: Activity) {
            try {
                recentResumedActivityRef = WeakReference(activity)
                val record = getOrCreateRecord(activity)
                record.stage = ActivityStage.RESUMED

                if (record.isOpenRequested) {
                    if (record.dialog == null) {
                        requestDisplayOrDefer(record, activity)
                    } else {
                        record.dialog?.onOwnerResumed()
                    }
                } else {
                    record.dialog?.onOwnerResumed()
                }
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivityResumed error", t)
                val record = records[activity]
                if (record != null) {
                    cleanupRecordDialog(record)
                }
            }
        }

        override fun onActivityPaused(activity: Activity) {
            try {
                val record = getOrCreateRecord(activity)
                record.stage = ActivityStage.PAUSED
                removePendingAttachListener(record)
                record.dialog?.onOwnerPaused()
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivityPaused error", t)
                val record = records[activity]
                if (record != null) {
                    cleanupRecordDialog(record)
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {
            try {
                val record = getOrCreateRecord(activity)
                record.stage = ActivityStage.STOPPED
                record.dialog?.onOwnerStopped()
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivityStopped error", t)
                val record = records[activity]
                if (record != null) {
                    cleanupRecordDialog(record)
                }
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            try {
                val record = records[activity]
                if (record != null && record.isOpenRequested) {
                    val dialogBundle = Bundle()
                    val composeState = if (record.dialog != null) {
                        try {
                            record.dialog?.saveComposeState()
                        } catch (t: Throwable) {
                            HookLog.e(TAG, "saveComposeState error in onActivitySaveInstanceState", t)
                            cleanupRecordDialog(record)
                            outState.remove(KEY_SETTINGS_DIALOG)
                            return
                        }
                    } else {
                        record.pendingRestoreBundle
                    }
                    if (composeState != null) {
                        dialogBundle.putBundle(KEY_COMPOSE_STATE, composeState)
                    }
                    outState.putBundle(KEY_SETTINGS_DIALOG, dialogBundle)
                } else {
                    outState.remove(KEY_SETTINGS_DIALOG)
                }
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivitySaveInstanceState error", t)
                val record = records[activity]
                if (record != null) {
                    cleanupRecordDialog(record)
                }
                outState.remove(KEY_SETTINGS_DIALOG)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            try {
                if (recentResumedActivityRef?.get() === activity) {
                    recentResumedActivityRef = null
                }
                val record = records.remove(activity)
                if (record != null) {
                    record.stage = ActivityStage.DESTROYED
                    cleanupRecordDialog(record)
                }
            } catch (t: Throwable) {
                HookLog.e(TAG, "onActivityDestroyed error", t)
            }
        }
    }
}
