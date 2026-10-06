package com.copilot.qqpet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.ui.compose.ComposeInjectionHost
import com.copilot.qqpet.ui.compose.QPetExpressiveTheme
import com.copilot.qqpet.ui.compose.QPetSettingsScreen
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.theme.HostTheme
import com.tencent.mobileqq.fragment.QPublicBaseFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 宿主 Fragment 容器里的设置页；super 类来自宿主 APK（compileOnly 桩只供编译期）。 */
class QQSettingFragment : QPublicBaseFragment() {

    private var composeHost: ComposeInjectionHost? = null
    private var state: SettingsState? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        HookLog.trace("QQSettingFragment", "onCreateView 已被调用")
        val context = requireContext()
        val dark = HostTheme.isNight(context)
        val host = ComposeInjectionHost()
        val settings = SettingsState(context, HookEntry.globalEngine)
        composeHost = host
        state = settings
        val composeView = host.createView(context) {
            QPetExpressiveTheme(dark = dark) {
                QPetSettingsScreen(state = settings) { requireActivity().finish() }
            }
        }
        // 外层容器只为了让 owner 标记比 ComposeView 更早打到祖先链上（宿主不提供 ViewTree*Owner）
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            host.installOwnersOnAttach(this)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        composeHost?.onCreate()
        composeHost?.onStart()
        state?.refresh()
        syncAccountData()
    }

    override fun onResume() {
        super.onResume()
        composeHost?.onResume()
        state?.refresh()
    }

    override fun onPause() {
        super.onPause()
        composeHost?.onPause()
    }

    override fun onDestroyView() {
        state?.detach()
        composeHost?.onDestroy()
        state = null
        composeHost = null
        super.onDestroyView()
    }

    /** 进入页面时校准账号与小宠 ID，并把学园 / 职业解锁状态与可雇佣好友刷新到界面 */
    private fun syncAccountData() {
        val context = context ?: return
        val activeEngine = HookEntry.globalEngine ?: return
        CoroutineScope(Dispatchers.IO).launch {
            activeEngine.verifyAndSyncAccountSession(context)
            val (_, remotePetId) = activeEngine.queryOwnPetAwait()
            val petId = if (!remotePetId.isNullOrEmpty() &&
                PetAdventureEngine.shouldUpdateCachedPetId(PetAdventureEngine.cachedPetId, remotePetId)
            ) {
                HookLog.w("QQSettingFragment", "🔄 [设置页核验] 发现新活跃小宠 ID: $remotePetId，覆写旧缓存")
                PetAdventureEngine.saveScopedPetId(context, remotePetId)
                remotePetId
            } else {
                PetAdventureEngine.cachedPetId ?: remotePetId
            }
            if (petId.isNullOrEmpty()) return@launch
            activeEngine.preloadAccountDataAwait(petId)
            activeEngine.queryPetAttributesAwait(petId)
            if (PetAdventureEngine.loadCachedHireableFriends(context).isEmpty()) {
                activeEngine.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
            }
        }
    }
}