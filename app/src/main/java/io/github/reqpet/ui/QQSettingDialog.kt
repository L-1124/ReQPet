package io.github.reqpet.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import io.github.reqpet.HookEntry
import io.github.reqpet.engine.PetAccountGateway
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.hook.HookLog
import io.github.reqpet.ui.compose.ComposeInjectionHost
import io.github.reqpet.ui.compose.QPetExpressiveTheme
import io.github.reqpet.ui.compose.QPetSettingsScreen
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.theme.HostTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 模块自建全屏设置窗口容器。
 *
 * 隔离宿主视图层级与事件路由，在独立 Dialog Window 内驱动 Compose 运行环境。
 */
internal class QQSettingDialog(
    private val activity: Activity,
    savedComposeState: Bundle?,
    onClosed: () -> Unit,
    private val showRequestTimestampMs: Long = RuntimeDiagnostics.nowMs(),
    private val openToDrawCookie: Int = SettingsTrace.nextAsyncCookie()
) : Dialog(activity, getThemeResId(activity)) {

    private val isDarkTheme = mutableStateOf(HostTheme.isNight(activity, phase = "init"))
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isReleased = false
    private var isRootAttached = false
    private var isFirstResume = true
    private var isFirstDrawHandled = false
    private var isOpenToDrawTracing = false
    private var isAccountSyncTriggered = false
    private var firstDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var pendingAfterDrawRunnable: Runnable? = null
    private var pendingThemeSyncRunnable: Runnable? = null
    private var onClosedCallback: (() -> Unit)? = onClosed

    private var composeHost: ComposeInjectionHost? = null
    private var state: SettingsState? = null
    private var composeView: ComposeView? = null
    private var rootView: FrameLayout? = null
    private var pageBackHandler: (() -> Boolean)? = null
    private var api33BackCallback: Any? = null

    private enum class OwnerStage {
        CREATED, STARTED, RESUMED, STOPPED
    }

    private var ownerStage = OwnerStage.CREATED

    init {
        isOpenToDrawTracing = SettingsTrace.beginAsync("settings.open_to_first_draw", openToDrawCookie)
        SettingsTrace.trace("settings.dialog_init") {
            try {
                setOwnerActivity(activity)
                setCanceledOnTouchOutside(false)

                requestWindowFeature(Window.FEATURE_NO_TITLE)
                val win = window ?: throw IllegalStateException("Dialog window is null")
                win.setWindowAnimations(android.R.style.Animation_Translucent)
                win.clearFlags(
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                )
                applyLegacySoftInputMode(win)
                applyTransparentSystemBars(win)
                configureSystemBarContrast(win)
                WindowCompat.setDecorFitsSystemWindows(win, false)

                val host = ComposeInjectionHost(savedComposeState)
                composeHost = host
                host.onCreate()

                val settings = SettingsTrace.trace("settings.state_init") {
                    SettingsState(activity, HookEntry.globalEngine)
                }
                state = settings

                val compose = SettingsTrace.trace("settings.create_view") {
                    host.createView(activity) {
                        QPetExpressiveTheme(dark = isDarkTheme.value) {
                            QPetSettingsScreen(
                                state = settings,
                                onBack = { dismiss() },
                                onBackHandlerChanged = { pageBackHandler = it }
                            )
                        }
                    }
                }
                composeView = compose

                val root = object : FrameLayout(activity) {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        super.onConfigurationChanged(newConfig)
                        try {
                            updateThemeAndSystemBars(newConfig, "configuration")
                            scheduleThemeSync()
                        } catch (t: Throwable) {
                            HookLog.w(TAG, "Configuration change handling failed: ${t.message}")
                        }
                    }

                    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                        super.onWindowFocusChanged(hasWindowFocus)
                        if (hasWindowFocus) scheduleThemeSync()
                    }
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            try {
                                isRootAttached = true
                                syncLifecycleState()
                            } catch (t: Throwable) {
                                HookLog.e(TAG, "Error handling root attach", t)
                                release(notifyClosed = true)
                            }
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            try {
                                isRootAttached = false
                                syncLifecycleState()
                            } catch (t: Throwable) {
                                HookLog.e(TAG, "Error handling root detach", t)
                                release(notifyClosed = true)
                            }
                        }
                    })
                    host.installOwnersOnAttach(
                        root = this,
                        boundaryView = win.decorView,
                        onError = { release(notifyClosed = true) }
                    )
                    addView(
                        compose,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                rootView = root

                setContentView(root)
                updateThemeAndSystemBars()
            } catch (t: Throwable) {
                HookLog.e(TAG, "Initialization failed, releasing allocations", t)
                release(notifyClosed = false)
                throw t
            }
        }
    }

    override fun show() {
        SettingsTrace.trace("settings.dialog_show") {
            if (isReleased) {
                HookLog.w(TAG, "show rejected: dialog instance has already been destroyed")
                return
            }
            if (activity.isFinishing || activity.isDestroyed) {
                HookLog.w(TAG, "show rejected: host activity is finishing or destroyed")
                release(notifyClosed = true)
                return
            }
            try {
                super.show()
                window?.apply {
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    decorView.setPadding(0, 0, 0, 0)
                }
                registerApi33BackCallback()
                updateThemeAndSystemBars()
                syncLifecycleState()
                registerFirstDrawListener()
            } catch (t: Throwable) {
                HookLog.e(TAG, "Dialog show failed, cleaning up", t)
                release(notifyClosed = true)
                throw t
            }
        }
    }

    private fun registerFirstDrawListener() {
        if (isFirstDrawHandled || isReleased) return
        val root = rootView ?: return
        try {
            val listener = ViewTreeObserver.OnDrawListener {
                try {
                    handleFirstDraw()
                } catch (t: Throwable) {
                    HookLog.w(TAG, "OnDrawListener callback error: ${t.message}")
                }
            }
            firstDrawListener = listener
            root.viewTreeObserver.addOnDrawListener(listener)
        } catch (t: Throwable) {
            HookLog.w(TAG, "Failed registering OnDrawListener: ${t.message}")
            triggerAccountSync()
        }
    }

    private fun handleFirstDraw() {
        if (isFirstDrawHandled || isReleased) return
        isFirstDrawHandled = true

        try {
            // ViewTreeObserver.OnDrawListener 观察到首次 draw 遍历启动（不代表绘制完成或屏幕呈现）
            val elapsedMs = RuntimeDiagnostics.nowMs() - showRequestTimestampMs
            SettingsTrace.instant("settings.first_draw")
            if (isOpenToDrawTracing) {
                isOpenToDrawTracing = false
                SettingsTrace.endAsync("settings.open_to_first_draw", openToDrawCookie)
            }
            RuntimeDiagnostics.event(
                "settings_first_draw",
                "elapsed_ms" to elapsedMs,
                "status" to "success"
            )

            val root = rootView
            val afterDrawRunnable = Runnable {
                pendingAfterDrawRunnable = null
                if (isReleased) return@Runnable
                try {
                    removeFirstDrawListener()
                    triggerAccountSync()
                } catch (t: Throwable) {
                    HookLog.w(TAG, "afterDrawRunnable error: ${t.message}")
                }
            }
            pendingAfterDrawRunnable = afterDrawRunnable

            val posted = try {
                root != null && root.post(afterDrawRunnable)
            } catch (_: Throwable) {
                false
            }

            if (!posted) {
                pendingAfterDrawRunnable = null
                // 禁止在 onDraw 遍历体内直接移除监听器（ViewTreeObserver API 禁止并抛异常），保留引用由 release() 兜底清理
                if (!isReleased) {
                    triggerAccountSync()
                }
            }
        } catch (t: Throwable) {
            HookLog.w(TAG, "handleFirstDraw error: ${t.message}")
        }
    }

    private fun removeFirstDrawListener() {
        val listener = firstDrawListener ?: return
        try {
            val root = rootView
            if (root != null) {
                val vto = root.viewTreeObserver
                if (vto.isAlive) {
                    vto.removeOnDrawListener(listener)
                    firstDrawListener = null
                } else {
                    firstDrawListener = null
                }
            }
        } catch (t: Throwable) {
            HookLog.w(TAG, "Failed removing OnDrawListener: ${t.message}")
        }
    }

    private fun triggerAccountSync() {
        if (isAccountSyncTriggered || isReleased) return
        isAccountSyncTriggered = true
        syncAccountData()
    }

    fun saveComposeState(): Bundle = SettingsTrace.trace("settings.save_state") {
        val bundle = Bundle()
        composeHost?.saveState(bundle)
        bundle
    }

    fun onOwnerStarted() {
        if (isReleased) return
        ownerStage = OwnerStage.STARTED
        syncLifecycleState()
    }

    fun onOwnerResumed() {
        SettingsTrace.trace("settings.owner_resumed") {
            if (isReleased) return
            ownerStage = OwnerStage.RESUMED
            updateThemeAndSystemBars(phase = "resume")
            scheduleThemeSync()
            if (isFirstResume) {
                // 首次 resume 紧接在构造后发生，SettingsState init 已获取最新缓存快照，跳过冗余即时 refresh
                isFirstResume = false
            } else {
                state?.refresh()
            }
            syncLifecycleState()
        }
    }

    fun onOwnerPaused() {
        if (isReleased) return
        ownerStage = OwnerStage.STARTED
        cancelPendingThemeSync()
        syncLifecycleState()
    }

    fun onOwnerStopped() {
        if (isReleased) return
        ownerStage = OwnerStage.STOPPED
        cancelPendingThemeSync()
        syncLifecycleState()
    }

    fun handleBack() {
        if (isReleased) return
        val handled = try {
            pageBackHandler?.invoke() == true
        } catch (t: Throwable) {
            HookLog.w(TAG, "pageBackHandler error: ${t.message}")
            false
        }
        if (!handled) {
            dismiss()
        }
    }

    /**
     * 兼容 API 26-32 及宿主未接入预测性返回时的物理返回/三键导航事件兜底。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    override fun dismiss() {
        release(notifyClosed = true)
    }

    override fun cancel() {
        release(notifyClosed = true)
    }

    private fun scheduleThemeSync() {
        if (isReleased || pendingThemeSyncRunnable != null) return
        val root = rootView ?: return
        try {
            val runnable = Runnable {
                pendingThemeSyncRunnable = null
                if (isReleased || ownerStage != OwnerStage.RESUMED ||
                    !root.isAttachedToWindow || activity.isFinishing || activity.isDestroyed
                ) return@Runnable
                updateThemeAndSystemBars(phase = "window_ready")
            }
            pendingThemeSyncRunnable = runnable
            if (!root.post(runnable)) pendingThemeSyncRunnable = null
        } catch (t: Throwable) {
            HookLog.w(TAG, "Failed scheduling theme synchronization: ${t.message}")
        }
    }

    private fun cancelPendingThemeSync() {
        val runnable = pendingThemeSyncRunnable ?: return
        pendingThemeSyncRunnable = null
        try {
            rootView?.removeCallbacks(runnable)
        } catch (t: Throwable) {
            HookLog.w(TAG, "Failed cancelling theme synchronization: ${t.message}")
        }
    }

    private fun updateThemeAndSystemBars(configuration: Configuration? = null, phase: String? = null) {
        try {
            val dark = HostTheme.isNight(activity, configuration, phase)
            if (isDarkTheme.value != dark) {
                isDarkTheme.value = dark
            }
            val win = window ?: return
            val insetsController = WindowCompat.getInsetsController(win, win.decorView)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.isAppearanceLightStatusBars = !dark
            insetsController.isAppearanceLightNavigationBars = !dark
        } catch (t: Throwable) {
            HookLog.w(TAG, "Failed updating system bars appearance: ${t.message}")
        }
    }

    private fun syncLifecycleState() {
        val host = composeHost ?: return
        if (isReleased) return

        if (!isShowing || !isRootAttached) {
            if (host.lifecycle.currentState > Lifecycle.State.CREATED) {
                host.onStop()
            }
            return
        }

        when (ownerStage) {
            OwnerStage.RESUMED -> {
                if (host.lifecycle.currentState < Lifecycle.State.STARTED) {
                    host.onStart()
                }
                if (host.lifecycle.currentState < Lifecycle.State.RESUMED) {
                    host.onResume()
                }
            }

            OwnerStage.STARTED -> {
                if (host.lifecycle.currentState < Lifecycle.State.STARTED) {
                    host.onStart()
                } else if (host.lifecycle.currentState > Lifecycle.State.STARTED) {
                    host.onPause()
                }
            }

            OwnerStage.STOPPED, OwnerStage.CREATED -> {
                if (host.lifecycle.currentState > Lifecycle.State.CREATED) {
                    host.onStop()
                }
            }
        }
    }

    private fun registerApi33BackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && api33BackCallback == null) {
            try {
                api33BackCallback = Api33BackHelper.register(this) {
                    handleBack()
                }
            } catch (t: Throwable) {
                HookLog.w(TAG, "Failed registering API 33 OnBackInvokedCallback: ${t.message}")
            }
        }
    }

    private fun unregisterApi33BackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = api33BackCallback
            if (callback != null) {
                api33BackCallback = null
                try {
                    Api33BackHelper.unregister(this, callback)
                } catch (t: Throwable) {
                    HookLog.w(TAG, "Failed unregistering API 33 OnBackInvokedCallback: ${t.message}")
                }
            }
        }
    }

    private fun release(notifyClosed: Boolean = true) {
        SettingsTrace.trace("settings.release") {
            if (isReleased) return
            isReleased = true
            cancelPendingThemeSync()

            // 1. 始终独立闭合异步 trace 跨度，不依赖 isFirstDrawHandled 状态
            if (isOpenToDrawTracing) {
                isOpenToDrawTracing = false
                try {
                    SettingsTrace.endAsync("settings.open_to_first_draw", openToDrawCookie)
                } catch (_: Throwable) {
                }
            }

            // 2. 移除未完成的 draw 遍历监听器与待执行的 post 回调（全异常隔离，绝不中断后续清理）
            try {
                pendingAfterDrawRunnable?.let {
                    rootView?.removeCallbacks(it)
                    pendingAfterDrawRunnable = null
                }
            } catch (t: Throwable) {
                HookLog.w(TAG, "Error removing pendingAfterDrawRunnable on release: ${t.message}")
            }

            try {
                removeFirstDrawListener()
            } catch (t: Throwable) {
                HookLog.w(TAG, "Error removing draw listener on release: ${t.message}")
            }

            if (!isFirstDrawHandled) {
                isFirstDrawHandled = true
                try {
                    val elapsedMs = RuntimeDiagnostics.nowMs() - showRequestTimestampMs
                    RuntimeDiagnostics.event(
                        "settings_first_draw",
                        "elapsed_ms" to elapsedMs,
                        "status" to "aborted"
                    )
                } catch (t: Throwable) {
                    HookLog.w(TAG, "Error emitting aborted first draw event: ${t.message}")
                }
            }
            try {
                syncScope.cancel()
            } catch (t: Throwable) {
                HookLog.w(TAG, "Error cancelling syncScope: ${t.message}")
            }

            pageBackHandler = null
            unregisterApi33BackCallback()

            if (isShowing) {
                try {
                    super.dismiss()
                } catch (t: Throwable) {
                    HookLog.w(TAG, "Error calling super.dismiss: ${t.message}")
                }
            }

            try {
                composeView?.disposeComposition()
            } catch (t: Throwable) {
                HookLog.w(TAG, "Error disposing composition: ${t.message}")
            }
            composeView = null
            rootView = null

            try {
                state?.detach()
            } catch (t: Throwable) {
                HookLog.w(TAG, "Error detaching SettingsState: ${t.message}")
            }
            state = null

            try {
                composeHost?.onDestroy()
            } catch (t: Throwable) {
                HookLog.w(TAG, "Error destroying composeHost: ${t.message}")
            }
            composeHost = null

            val callback = onClosedCallback
            onClosedCallback = null
            if (notifyClosed) {
                try {
                    callback?.invoke()
                } catch (t: Throwable) {
                    HookLog.e(TAG, "Error invoking onClosed callback", t)
                }
            }
        }
    }

    private fun syncAccountData() {
        if (isReleased) return
        val appContext = activity.applicationContext ?: activity
        val activeEngine = HookEntry.globalEngine ?: run {
            HookLog.w(TAG, "syncAccountData: globalEngine is null, skipping")
            return
        }
        try {
            syncScope.launch {
                val syncCookie = SettingsTrace.nextAsyncCookie()
                val started = SettingsTrace.beginAsync("settings.account_sync", syncCookie)
                try {
                    activeEngine.withAccountSession(appContext, "settings_refresh") {
                        val (_, remotePetId) = activeEngine.queryOwnPetAwait()
                        val petId = if (!remotePetId.isNullOrEmpty() &&
                            PetPureCalculations.shouldUpdateCachedPetId(PetAdventureEngine.cachedPetId, remotePetId)
                        ) {
                            HookLog.w(TAG, "[设置页核验] 发现新活跃小宠 ID: $remotePetId，覆写旧缓存")
                            PetAdventureEngine.saveScopedPetId(appContext, remotePetId)
                            remotePetId
                        } else {
                            PetAdventureEngine.cachedPetId ?: remotePetId
                        }
                        if (petId.isNullOrEmpty()) return@withAccountSession
                        activeEngine.preloadAccountDataAwait(petId)
                        activeEngine.queryPetAttributesAwait(petId)
                        if (PetAccountGateway.loadCachedHireableFriends(appContext).isEmpty()) {
                            activeEngine.fetchAllHireableFriendsAwait(appContext, enrichSelectedAndTop = false)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    HookLog.e(TAG, "syncAccountData failed", t)
                } finally {
                    if (started) {
                        SettingsTrace.endAsync("settings.account_sync", syncCookie)
                    }
                }
            }
        } catch (t: Throwable) {
            HookLog.w(TAG, "syncAccountData launch error: ${t.message}")
        }
    }

    /**
     * API 26-29 独立 Dialog Window 调整布局以响应软键盘弹起所必需。
     */
    @Suppress("DEPRECATION")
    private fun applyLegacySoftInputMode(win: Window) {
        win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /**
     * API 26-34 独立 Dialog Window 消除系统栏黑底实现全屏沉浸所必需。
     */
    @Suppress("DEPRECATION")
    private fun applyTransparentSystemBars(win: Window) {
        win.statusBarColor = Color.TRANSPARENT
        win.navigationBarColor = Color.TRANSPARENT
    }

    /**
     * API 29+ 禁用系统栏强制对比度遮罩，避免系统叠加半透明灰色蒙层破坏全透明沉浸。
     */
    @Suppress("DEPRECATION")
    private fun configureSystemBarContrast(win: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
            win.isStatusBarContrastEnforced = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33BackHelper {
        fun register(dialog: Dialog, onBack: () -> Unit): Any {
            val callback = android.window.OnBackInvokedCallback {
                onBack()
            }
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            return callback
        }

        fun unregister(dialog: Dialog, callback: Any) {
            if (callback is android.window.OnBackInvokedCallback) {
                dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
    }

    companion object {
        private const val TAG = "QQSettingDialog"

        private fun getThemeResId(activity: Activity): Int =
            if (HostTheme.isNight(activity)) {
                android.R.style.Theme_Material_NoActionBar
            } else {
                android.R.style.Theme_Material_Light_NoActionBar
            }
    }
}
