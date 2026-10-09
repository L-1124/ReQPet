package io.github.reqpet.ui.compose

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.reqpet.R
import io.github.reqpet.hook.HookLog

/**
 * 注入式 Compose 运行环境宿主：提供模块闭环的 ViewTree*Owner。
 *
 * 沿视图祖先链安装模块 LifecycleOwner、ViewModelStoreOwner 与 SavedStateRegistryOwner，
 * 止于指定的边界视图（Dialog DecorView），防止作用域逸出到外部窗口。
 */
class ComposeInjectionHost(savedState: Bundle? = null) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(savedState)
    }

    fun createView(context: Context, content: @Composable () -> Unit): ComposeView =
        ComposeView(context).apply {
            id = R.id.qpet_settings_compose_view
            tagOwners(this)
            setContent(content)
        }

    fun saveState(outState: Bundle) {
        savedStateController.performSave(outState)
    }

    fun installOwnersOnAttach(
        root: View,
        boundaryView: View,
        onError: ((Throwable) -> Unit)? = null
    ) {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                try {
                    var node: View? = v
                    while (node != null) {
                        tagOwners(node)
                        if (node === boundaryView) {
                            break
                        }
                        node = node.parent as? View
                    }
                } catch (t: Throwable) {
                    HookLog.e("ComposeInjectionHost", "Failed installing ViewTree owners on attach", t)
                    try {
                        onError?.invoke(t)
                    } catch (cbError: Throwable) {
                        HookLog.e("ComposeInjectionHost", "Error invoking onError callback", cbError)
                    }
                }
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

    fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
