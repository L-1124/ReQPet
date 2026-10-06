package com.copilot.qqpet.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.HookLog as Log
import com.copilot.qqpet.ui.section.SettingActionCard
import com.copilot.qqpet.ui.section.SettingCareerCard
import com.copilot.qqpet.ui.section.SettingDailyCard
import com.copilot.qqpet.ui.section.SettingLogCard
import com.copilot.qqpet.ui.section.SettingMoreCard
import com.copilot.qqpet.ui.section.SettingStatusCard
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.CardUiBuilder
import com.copilot.qqpet.ui.util.UiAnimUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 设置页内容与生命周期；容器由 [QQSettingFragment] 提供。 */
class QQSettingPage(
    private val context: Context,
    private val engine: PetAdventureEngine?,
    private val onBack: () -> Unit
) {
    private companion object {
        const val TICKER_INTERVAL_MS = 1000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val colors = ThemeColors.get(ThemeColors.isNightTheme(context))
    private val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)

    private lateinit var statusCardHelper: SettingStatusCard
    private lateinit var careerCard: SettingCareerCard
    private lateinit var logCard: SettingLogCard

    private val tickerRunnable = object : Runnable {
        override fun run() {
            statusCardHelper.refreshLiveStatus()
            mainHandler.postDelayed(this, TICKER_INTERVAL_MS)
        }
    }

    fun build(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusCardHelper = SettingStatusCard(context, colors, engine) { onBack() }
        root.addView(statusCardHelper.buildTopBar())
        root.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 14),
                UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 36)
            )
        }
        CardUiBuilder.addSectionHeader(content, "总开关", colors)
        val masterCard = CardUiBuilder.createGroupCard(context, colors)
        CardUiBuilder.addSimpleToggleRow(
            masterCard, context, colors, prefs, engine,
            "全自动托管",
            "默认关闭；关闭时模块不发起任何请求，下面的功能开关也不会生效",
            PreferencesHelper.KEY_MASTER_ENABLED, false, true
        )
        masterCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiAnimUtils.dp(context, 16)) }
        content.addView(masterCard)
        content.addView(statusCardHelper.buildStatusCard())
        careerCard = SettingCareerCard(context, colors, prefs, engine)
        careerCard.build(content)
        SettingDailyCard(context, colors, prefs, engine).build(content)
        SettingActionCard(context, colors, engine) { statusCardHelper.refreshLiveStatus() }.build(content)
        logCard = SettingLogCard(context, colors)
        logCard.build(content)
        SettingMoreCard(context, colors).build(content)

        root.addView(ScrollView(context).apply {
            addView(content)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        })
        return root
    }

    fun onAttach() {
        mainHandler.post(tickerRunnable)
        logCard.attach()
        syncAccountData()
    }

    fun onDetach() {
        mainHandler.removeCallbacks(tickerRunnable)
        logCard.detach()
    }

    /** 进入页面时校准账号与小宠 ID，并把学园 / 职业解锁状态刷新到界面 */
    private fun syncAccountData() {
        val activeEngine = engine ?: HookEntry.globalEngine ?: return
        CoroutineScope(Dispatchers.IO).launch {
            activeEngine.verifyAndSyncAccountSession(context)
            val (_, remotePetId) = activeEngine.queryOwnPetAwait()
            val petId = if (!remotePetId.isNullOrEmpty() &&
                PetAdventureEngine.shouldUpdateCachedPetId(PetAdventureEngine.cachedPetId, remotePetId)
            ) {
                Log.w("QQSettingPage", "🔄 [设置页核验] 发现新活跃小宠 ID: $remotePetId，覆写旧缓存")
                PetAdventureEngine.saveScopedPetId(context, remotePetId)
                remotePetId
            } else {
                PetAdventureEngine.cachedPetId ?: remotePetId
            }
            if (petId.isNullOrEmpty()) return@launch
            val preloaded = activeEngine.preloadAccountDataAwait(petId)
            activeEngine.queryPetAttributesAwait(petId)
            mainHandler.post {
                careerCard.updateSchoolUnlockStates(preloaded.schoolDetails)
                careerCard.updateWorkUnlockStates(preloaded.workPlaces, preloaded.workJobs)
                statusCardHelper.refreshLiveStatus()
            }
            if (PetAdventureEngine.loadCachedHireableFriends(context).isEmpty()) {
                activeEngine.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
            }
        }
    }
}
