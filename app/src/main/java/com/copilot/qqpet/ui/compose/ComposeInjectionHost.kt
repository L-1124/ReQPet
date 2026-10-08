package com.copilot.qqpet.ui.compose

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
import com.copilot.qqpet.R

/**
 * 注入式 Compose 运行环境宿主：提供模块闭环的 ViewTree*Owner。
 *
 * WindowRecomposer 从 android.R.id.content 的直接子视图（宿主 Fragment 容器）向上查
 * ViewTree*Owner，因此 attach 时沿祖先链把模块 owner 打满。模块与宿主 tag key 不同，
 * 在 mKeyedTags 中共存互不覆盖。
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

    fun installOwnersOnAttach(root: View) {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                var node: View? = v
                while (node != null) {
                    tagOwners(node)
                    node = node.parent as? View
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

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
