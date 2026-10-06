package com.copilot.qqpet.hook

import android.app.Activity
import com.copilot.qqpet.ui.QQSettingFragment
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 让宿主的通用 Fragment 容器能承载模块自己的设置页。
 *
 * 宿主 QPublicFragmentActivity#createFragment() 用 Class.forName(默认 ClassLoader) 实例化
 * Intent 里 public_fragment_class 指定的类，看不到模块类；这里在它执行前用自己的类构造好实例。
 * 非模块类名一律放行走宿主原逻辑。
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val className = activity.intent?.getStringExtra(KEY_FRAGMENT_CLASS)
                    // 宿主自己的 Fragment 不记录，只关心模块类是否被正确构造
                    if (className == null || !className.startsWith(MODULE_CLASS_PREFIX)) return
                    val fragment = buildFragment(activity)
                    if (fragment == null) return
                    param.result = fragment
                    HookLog.trace(TAG, "已构造模块 Fragment: $className")
                }
            })
            installed = true
            HookLog.trace(TAG, "已挂钩 $ACTIVITY_CLASS#$METHOD_CREATE_FRAGMENT")
        } catch (t: Throwable) {
            HookLog.trace(TAG, "挂钩通用 Fragment 容器失败", t)
        }
    }

    private fun buildFragment(activity: Activity): Any? {
        return try {
            QQSettingFragment().apply { arguments = activity.intent?.extras }
        } catch (t: Throwable) {
            HookLog.trace(TAG, "QQSettingFragment() 抛异常", t)
            null
        }
    }
}