package com.copilot.qqpet.hook

import android.app.Activity
import com.copilot.qqpet.ui.QQSettingFragment

/**
 * 宿主 QPublicFragmentActivity#createFragment() 用 Class.forName 实例化 public_fragment_class
 * 指定的类，看不到模块类；这里替它构造，非模块类名走宿主原逻辑。
 */
object PublicFragmentHostHook {

    private const val TAG = "QQPetFragmentHost"
    private const val ACTIVITY_CLASS = "com.tencent.mobileqq.activity.QPublicFragmentActivity"
    private const val METHOD_CREATE_FRAGMENT = "createFragment"
    private const val KEY_FRAGMENT_CLASS = "public_fragment_class"
    private const val MODULE_CLASS_PREFIX = "com.copilot.qqpet."

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        try {
            val hostClass = classLoader.loadClass(ACTIVITY_CLASS)
            val method = hostClass.getDeclaredMethod(METHOD_CREATE_FRAGMENT)
            HookApi.hook(method).intercept { chain ->
                val activity = chain.thisObject as? Activity
                val className = activity?.intent?.getStringExtra(KEY_FRAGMENT_CLASS)
                if (activity == null || className == null || !className.startsWith(MODULE_CLASS_PREFIX)) {
                    chain.proceed()
                } else {
                    val fragment = buildFragment(activity)
                    if (fragment == null) {
                        chain.proceed()
                    } else {
                        HookLog.trace(TAG, "已构造模块 Fragment: $className")
                        fragment
                    }
                }
            }
            installed = true
            HookLog.trace(TAG, "已挂钩 $ACTIVITY_CLASS#$METHOD_CREATE_FRAGMENT")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.trace(TAG, "挂钩通用 Fragment 容器失败", t)
        }
    }

    private fun buildFragment(activity: Activity): Any? {
        return try {
            QQSettingFragment().apply { arguments = activity.intent?.extras }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.trace(TAG, "QQSettingFragment() 抛异常", t)
            null
        }
    }
}