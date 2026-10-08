package com.copilot.qqpet.hook

import android.content.Context
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy

/**
 * QQ 设置页通用卡片与条目实例工厂：
 * 负责通过标准反射与代理机制安全构造设置条目与分组包装，隔离宿主类结构差异。
 */
object QQSettingItemFactory {

    private const val TAG = "QQSettingItemFactory"
    private const val DUMMY_VIEW_ID = 999520

    fun hasStandardItemConstructor(cls: Class<*>): Boolean {
        for (c in cls.constructors) {
            val pts = c.parameterTypes
            if (pts.size in 4..7 &&
                Context::class.java.isAssignableFrom(pts[0]) &&
                pts[1] == Integer.TYPE &&
                CharSequence::class.java.isAssignableFrom(pts[2]) &&
                pts[3] == Integer.TYPE
            ) {
                return true
            }
        }
        return false
    }

    fun createItemInstance(candidateClasses: List<Class<*>>, contractCls: Class<*>?, ctx: Context): Any? {
        val iconRes = resolveIconResource(ctx)
        for (cls in candidateClasses) {
            if (contractCls != null && !contractCls.isAssignableFrom(cls)) continue

            for (c in cls.constructors) {
                c.isAccessible = true
                val instance = tryConstructItem(c, ctx, iconRes)
                if (instance != null && (contractCls == null || contractCls.isInstance(instance))) {
                    HookLog.log(TAG, "成功实例化安全条目处理器: ${cls.name}")
                    return instance
                }
            }
        }
        return null
    }

    private fun tryConstructItem(constructor: Constructor<*>, ctx: Context, iconRes: Int): Any? {
        return try {
            val pTypes = constructor.parameterTypes
            val args = arrayOfNulls<Any>(pTypes.size)
            for (i in pTypes.indices) {
                val pt = pTypes[i]
                args[i] = when {
                    Context::class.java.isAssignableFrom(pt) -> ctx
                    pt == Integer.TYPE -> if (i == 1) DUMMY_VIEW_ID else iconRes
                    CharSequence::class.java.isAssignableFrom(pt) -> "Q宠后台伴侣"
                    pt == String::class.java -> "纯后台全自动调度"
                    pt == java.lang.Boolean.TYPE -> true
                    else -> null
                }
            }
            constructor.newInstance(*args)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    fun resolveIconResource(ctx: Context): Int {
        var iconRes = ctx.resources.getIdentifier("qui_tuning", "drawable", ctx.packageName)
        if (iconRes == 0) {
            iconRes = ctx.resources.getIdentifier("qq_setting_me_icon", "drawable", ctx.packageName)
        }
        return iconRes
    }

    fun bindItemContentAndClick(
        item: Any,
        classLoader: ClassLoader,
        onEntryClick: () -> Unit
    ) {
        val itemCls = item.javaClass
        for (f in itemCls.declaredFields) {
            f.isAccessible = true
            try {
                val isStringType = CharSequence::class.java.isAssignableFrom(f.type) || f.type == String::class.java
                if (isStringType) {
                    if (f.name == "g" || f.name.equals("title", ignoreCase = true)) {
                        f.set(item, "Q宠后台伴侣")
                    } else if (f.name == "i" || f.name.equals(
                            "subTitle",
                            ignoreCase = true
                        ) || f.name.equals("subtitle", ignoreCase = true)
                    ) {
                        f.set(item, "纯后台全自动调度")
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }

        val clickListenerMethod = itemCls.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 && (
                    m.parameterTypes[0].name.contains("Function0") ||
                            m.parameterTypes[0].name.contains("OnClickListener")
                    )
        } ?: return

        try {
            val paramType = clickListenerMethod.parameterTypes[0]
            val clickProxy = Proxy.newProxyInstance(classLoader, arrayOf(paramType)) { _, method, _ ->
                if (method.name == "invoke" || method.name == "onClick") {
                    onEntryClick()
                    val unitCls = classLoader.loadClass("kotlin.Unit")
                    return@newProxyInstance unitCls.getField("INSTANCE").get(null)
                }
                null
            }
            clickListenerMethod.invoke(item, clickProxy)
            HookLog.log(TAG, "成功绑定点击代理！")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.log(TAG, "绑定点击代理失败: ${t.message}")
        }
    }

    fun createSettingGroup(groupCls: Class<*>, item: Any): Any? {
        for (c in groupCls.constructors) {
            c.isAccessible = true
            try {
                val pTypes = c.parameterTypes
                if (pTypes.isNotEmpty() && List::class.java.isAssignableFrom(pTypes[0])) {
                    val singleItemList = listOf(item)
                    val args = arrayOfNulls<Any>(pTypes.size)
                    args[0] = singleItemList
                    for (i in 1 until pTypes.size) {
                        val pt = pTypes[i]
                        args[i] = when {
                            CharSequence::class.java.isAssignableFrom(pt) -> ""
                            pt == Integer.TYPE -> 6
                            else -> null
                        }
                    }
                    return c.newInstance(*args)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return null
    }
}
