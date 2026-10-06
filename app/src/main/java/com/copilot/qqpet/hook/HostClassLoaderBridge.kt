package com.copilot.qqpet.hook

import java.net.URL

/**
 * LSPosed 给模块的 ClassLoader，其 parent 不是宿主 ClassLoader，模块类直接继承宿主类会
 * NoClassDefFoundError；这里把 parent 换成按包名分流的 shim：宿主包名走宿主 loader，其余走原 parent。
 * 思路同 XAutoDaily 的 XAClassLoader#injectClassLoader。
 */
class HostAwareClassLoader(
    originalParent: ClassLoader,
    private val hostClassLoader: ClassLoader
) : ClassLoader(originalParent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (isHostClass(name)) hostClassLoader.loadClass(name) else super.loadClass(name, resolve)

    override fun getResource(name: String): URL? =
        super.getResource(name) ?: hostClassLoader.getResource(name)

    companion object {
        private val HOST_PREFIXES = arrayOf(
            "com.tencent.mobileqq.",
            "com.tencent.common.app.",
            "com.tencent.qphone.base.",
            "com.tencent.widget.",
            "com.tencent.qqnt.",
            "com.tencent.biz.",
            "com.tencent.richframework.",
            "com.tencent.qqmini.",
            "cooperation.",
            "mqq.",
            "oicq.",
            "com.qq.",
        )

        fun isHostClass(name: String): Boolean = HOST_PREFIXES.any { name.startsWith(it) }
    }
}

object HostClassLoaderBridge {

    private const val TAG = "QQPetClassLoader"
    private const val HOST_FRAGMENT_BASE = "com.tencent.mobileqq.fragment.QPublicBaseFragment"

    @Volatile
    private var installed = false

    /**
     * @param moduleLoader 模块自己的 ClassLoader（本模块类的定义 loader）
     * @param hostLoader   宿主 ClassLoader（即 lpparam.classLoader）
     * @return 宿主类是否已可解析
     */
    fun install(moduleLoader: ClassLoader, hostLoader: ClassLoader): Boolean {
        if (installed) return true
        return try {
            val parentField = ClassLoader::class.java.getDeclaredField("parent")
            parentField.isAccessible = true
            val originalParent = parentField.get(moduleLoader) as? ClassLoader
            if (originalParent !is HostAwareClassLoader) {
                parentField.set(moduleLoader, HostAwareClassLoader(originalParent ?: hostLoader, hostLoader))
            }
            // 自检：改完之后必须能解析出宿主基类，否则方案不成立
            moduleLoader.loadClass(HOST_FRAGMENT_BASE)
            installed = true
            true
        } catch (t: Throwable) {
            HookLog.trace(TAG, "接入宿主分流 shim 失败", t)
            false
        }
    }
}
