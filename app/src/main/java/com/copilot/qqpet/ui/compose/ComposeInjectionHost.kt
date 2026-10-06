package com.copilot.qqpet.ui.compose

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 注入式 Compose 的宿主环境。
 *
 * 宿主 Activity 的 ViewTree*Owner 是宿主那份 androidx 打的标记，我们模块里的副本读不到，
 * 所以这里自建一套 Owner 挂在 ComposeView 自己身上，生命周期由承载它的 Fragment 驱动。
 */
class ComposeInjectionHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
    }

    fun createView(context: Context, content: @Composable () -> Unit): ComposeView =
        ComposeView(context).apply {
            tagOwners(this)
            setContent(content)
        }

    /**
     * 页面根视图 attach 时，沿祖先链把 owner 打满：
     * 宿主 Activity 不提供 ViewTree*Owner，而 Compose 是拿 rootView 去找的，
     * 只挂在 ComposeView 自己身上不够。根视图比 ComposeView 先收到 attach，来得及。
     */
    fun installOwnersOnAttach(root: View) {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // 只标到宿主 fragment 容器为止：Compose 会从 rootView 找 owner，容器那一层必须有；
                // 再往上打到 Activity/DecorView 没有意义，且会污染宿主的视图链。
                tagOwners(v)
                (v.parent as? View)?.let { tagOwners(it) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun tagOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    fun onCreate() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
