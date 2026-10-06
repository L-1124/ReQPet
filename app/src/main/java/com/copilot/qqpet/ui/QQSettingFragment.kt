package com.copilot.qqpet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.hook.HookLog
import com.tencent.mobileqq.fragment.QPublicBaseFragment

/**
 * 注入在 QQ 通用 Fragment 容器（QPublicFragmentActivity）中的设置页，
 * 与宿主原生设置页（MainSettingFragment）同形态：进宿主返回栈、沿用宿主窗口与转场。
 *
 * 注意：本类由模块 classloader 加载，super 类来自宿主 APK（compileOnly 桩提供编译期类型）。
 */
class QQSettingFragment : QPublicBaseFragment() {

    private var page: QQSettingPage? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        HookLog.trace("QQSettingFragment", "onCreateView 已被调用")
        val created = QQSettingPage(requireContext(), HookEntry.globalEngine) { requireActivity().finish() }
        page = created
        return created.build()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        page?.onAttach()
    }

    override fun onDestroyView() {
        page?.onDetach()
        page = null
        super.onDestroyView()
    }
}
