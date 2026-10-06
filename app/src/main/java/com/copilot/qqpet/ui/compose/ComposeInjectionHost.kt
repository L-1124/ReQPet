package com.copilot.qqpet.ui.compose

import android.content.Context
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

/**
 * 注入式 Compose 运行环境宿主。
 *
 * 为 Fragment 自身视图树提供模块闭环的 ViewTree*Owner，满足 Compose 向上遍历需求，
 * 绝不向外污染宿主 Activity 的 DecorView。
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
     * 页面根视图 attach 时沿祖先链挂载 Owner 标签：
     * Compose 的 WindowRecomposer 会找到 contentChild（即宿主的 Fragment 容器 #ckj），
     * 并从该容器向上查找 ViewTree*Owner。因模块与宿主使用不同的 tag key，两套标签在
     * View 的 mKeyedTags 中独立共存，互不干扰覆盖。
     */
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
