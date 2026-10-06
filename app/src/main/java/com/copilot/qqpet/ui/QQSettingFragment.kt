package com.copilot.qqpet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.hook.HookLog
import com.tencent.mobileqq.fragment.QPublicBaseFragment

/** 宿主 Fragment 容器里的设置页；super 类来自宿主 APK（compileOnly 桩只供编译期）。 */
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
