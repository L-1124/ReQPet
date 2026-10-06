package com.copilot.qqpet.hook

import android.app.Activity
import android.content.Context
import android.util.Log
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.ui.QQSettingDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object QQSettingInjector {

    private const val TAG = "QQSettingInjector"
    private const val DEFAULT_ITEM_INDEX = 2

    @Volatile
    var isHooked = false
        private set

    fun resetHookState() {
        isHooked = false
    }

    fun inject(classLoader: ClassLoader) {
        if (isHooked) return

        val providerClassNames = resolveProviderClassNames()
        for (className in providerClassNames) {
            val providerCls = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                continue
            }

            val getListMethod = providerCls.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 &&
                        Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        List::class.java.isAssignableFrom(m.returnType)
            } ?: continue

            try {
                XposedBridge.hookMethod(getListMethod, object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as? Context ?: return
                        val groupList = param.result as? MutableList<Any> ?: return
                        if (groupList.isEmpty()) return

                        try {
                            handleSettingListInjection(ctx, groupList, classLoader, providerCls)
                        } catch (t: Throwable) {
                            HookLog.log(TAG, "挂载异常: ${Log.getStackTraceString(t)}")
                        }
                    }
                })
                isHooked = true
                HookLog.log(TAG, "成功挂钩设置项提供者: $className (API 82)")
                break
            } catch (t: Throwable) {
                HookLog.log(TAG, "挂钩 $className 异常: ${t.message}")
            }
        }
    }

    private fun resolveProviderClassNames(): List<String> {
        val list = mutableListOf(
            "com.tencent.mobileqq.setting.main.b",
            "com.tencent.mobileqq.setting.main.MainSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.NewSettingConfigProvider"
        )
        // 应对 QQ 小版本混淆字母漂移
        for (ch in 'a'..'z') {
            val name = "com.tencent.mobileqq.setting.main.$ch"
            if (!list.contains(name)) list.add(name)
        }
        return list
    }

    private fun isAlreadyInjected(groupList: List<Any>): Boolean {
        for (item in groupList) {
            try {
                val titleField = item.javaClass.declaredFields.firstOrNull { it.type == CharSequence::class.java }
                if (titleField != null) {
                    titleField.isAccessible = true
                    val titleVal = titleField.get(item)?.toString()
                    if (titleVal?.contains("Q宠后台伴侣") == true) return true
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun handleSettingListInjection(
        ctx: Context,
        groupList: MutableList<Any>,
        classLoader: ClassLoader,
        providerCls: Class<*>
    ) {
        if (isAlreadyInjected(groupList)) return

        val sampleGroup = selectValidSampleGroup(groupList) ?: run {
            HookLog.log(TAG, "未发现可用的常规条目分组，安全跳过注入")
            return
        }
        val groupCls = sampleGroup.javaClass

        val (contractCls, candidateClasses) = resolveContractAndCandidates(sampleGroup, classLoader, providerCls)
        if (candidateClasses.isEmpty()) {
            HookLog.log(TAG, "未发现可用的设置项候选类，放弃注入防崩溃")
            return
        }

        val newItem = QQSettingItemFactory.createItemInstance(candidateClasses, contractCls, ctx) ?: run {
            HookLog.log(TAG, "无法安全实例化任何条目处理器，安全跳过注入")
            return
        }

        QQSettingItemFactory.bindItemContentAndClick(newItem, classLoader) {
            onSettingEntryClick(ctx)
        }

        val newGroup = QQSettingItemFactory.createSettingGroup(groupCls, newItem) ?: run {
            HookLog.log(TAG, "创建 SettingGroup 失败，跳过卡片注入")
            return
        }

        if (groupList.size >= DEFAULT_ITEM_INDEX) {
            groupList.add(DEFAULT_ITEM_INDEX, newGroup)
        } else {
            groupList.add(newGroup)
        }
        HookLog.log(TAG, "🎯 成功安全注入「Q宠后台伴侣」专属卡片 (API 82)！")
    }

    private fun selectValidSampleGroup(groupList: List<Any>): Any? {
        for (group in groupList) {
            val items = extractSampleItems(group)
            if (items.isEmpty()) continue
            val hasExcluded = items.any { isExcludedProcessor(it.javaClass) }
            if (!hasExcluded) return group
        }
        return if (groupList.size > 1) groupList[1] else groupList.firstOrNull()
    }

    private fun isExcludedProcessor(cls: Class<*>): Boolean {
        val name = cls.name.lowercase()
        return name.contains("search") ||
                name.endsWith(".processor.t") ||
                name.contains("account") ||
                name.contains("security")
    }

    private fun resolveProcessorFromProvider(providerCls: Class<*>): Class<*>? {
        for (m in providerCls.methods) {
            if (m.parameterTypes.size == 1 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.returnType.name.contains("com.tencent.mobileqq.setting.processor")
            ) {
                return m.returnType
            }
        }
        return null
    }

    fun resolveContractAndCandidates(
        sampleGroup: Any?,
        classLoader: ClassLoader,
        providerCls: Class<*>? = null
    ): Pair<Class<*>?, List<Class<*>>> {
        val candidates = mutableListOf<Class<*>>()

        // 1. 优先从 Provider 工厂方法获取标准通用条目处理器类型
        if (providerCls != null) {
            val providerType = resolveProcessorFromProvider(providerCls)
            if (providerType != null && QQSettingItemFactory.hasStandardItemConstructor(providerType)) {
                candidates.add(providerType)
            }
        }

        // 2. 尝试已知成熟通用的设置项处理器类名
        val wellKnownNames = listOf(
            "com.tencent.mobileqq.setting.processor.i",
            "com.tencent.mobileqq.setting.main.processor.i",
            "com.tencent.mobileqq.setting.processor.SimpleItemProcessor",
            "com.tencent.mobileqq.setting.main.processor.SimpleItemProcessor",
            "com.tencent.mobileqq.setting.processor.d"
        )
        for (name in wellKnownNames) {
            try {
                val cls = Class.forName(name, false, classLoader)
                if (QQSettingItemFactory.hasStandardItemConstructor(cls) && !candidates.contains(cls)) {
                    candidates.add(cls)
                }
            } catch (_: Throwable) {}
        }

        // 3. 动态探测 sampleGroup 中的条目（排除专有业务类与搜索组件）
        val sampleItems = if (sampleGroup != null) extractSampleItems(sampleGroup) else emptyList()
        val contractCls = detectContractClass(sampleItems)

        for (item in sampleItems) {
            val cls = item.javaClass
            if (isExcludedProcessor(cls)) continue
            if (contractCls == null || contractCls.isAssignableFrom(cls)) {
                if (!candidates.contains(cls)) candidates.add(cls)
            }
        }

        // 4. 同包下扫描继承并验证契约
        if (candidates.isEmpty() && contractCls != null) {
            val pkg = contractCls.name.substringBeforeLast('.')
            for (ch in 'a'..'z') {
                try {
                    val cls = Class.forName("$pkg.$ch", false, classLoader)
                    if (isExcludedProcessor(cls)) continue
                    if (contractCls.isAssignableFrom(cls) && !candidates.contains(cls)) {
                        candidates.add(cls)
                    }
                } catch (_: Throwable) {}
            }
        }

        return Pair(contractCls, candidates)
    }

    private fun extractSampleItems(sampleGroup: Any): List<Any> {
        for (f in sampleGroup.javaClass.declaredFields) {
            f.isAccessible = true
            val obj = try { f.get(sampleGroup) } catch (_: Throwable) { null }
            if (obj is List<*> && obj.isNotEmpty()) {
                val list = obj.filterNotNull()
                if (list.isNotEmpty()) return list
            }
        }
        return emptyList()
    }

    fun detectContractClass(sampleItems: List<Any>): Class<*>? {
        if (sampleItems.isEmpty()) return null
        val firstItem = sampleItems.first()
        var current: Class<*>? = firstItem.javaClass.superclass
        var baseSettingClass: Class<*>? = null
        while (current != null && current != Any::class.java) {
            if (current.name.contains("com.tencent.mobileqq.setting")) {
                baseSettingClass = current
            }
            current = current.superclass
        }
        if (baseSettingClass != null) return baseSettingClass

        for (iface in firstItem.javaClass.interfaces) {
            if (iface.name.contains("com.tencent.mobileqq.setting")) return iface
        }
        val superCls = firstItem.javaClass.superclass
        return if (superCls != Any::class.java) superCls else firstItem.javaClass
    }

    private fun onSettingEntryClick(context: Context) {
        HookLog.log(TAG, "⚡ 用户在 QQ 设置中点击了「Q宠后台伴侣」！")
        try {
            HookEntry.globalEngine?.startBackgroundLoop(context.applicationContext)
            if (context is Activity) {
                context.runOnUiThread {
                    QQSettingDialog.show(context, HookEntry.globalEngine)
                }
            } else {
                HookLog.log(TAG, "设置入口宿主不是 Activity，无法打开设置弹窗")
            }
        } catch (t: Throwable) {
            HookLog.log(TAG, "调起伴侣控制弹窗失败: ${t.message}")
        }
    }
}
