package com.copilot.qqpet.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.HookLog as Log
import com.copilot.qqpet.ui.component.AppleSegmentedControl
import com.copilot.qqpet.ui.component.AppleSwitchView
import com.copilot.qqpet.ui.component.SegmentItem
import com.copilot.qqpet.ui.component.WorkPlaceOption
import com.copilot.qqpet.ui.section.SettingActionCard
import com.copilot.qqpet.ui.section.SettingCareerCard
import com.copilot.qqpet.ui.section.SettingDailyCard
import com.copilot.qqpet.ui.section.SettingMoreCard
import com.copilot.qqpet.ui.section.SettingStatusCard
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.SettingConfigSyncer
import com.copilot.qqpet.ui.util.UiAnimUtils
import com.copilot.qqpet.ui.util.UiDescUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 遵循 Apple Design 顶级审美标准的 QQ 原生二级设置页面 (v1.0.35 重构解耦版)
 * 纯容器宿主：负责生命周期、全屏沉浸式容器构建与 Section 卡片组装
 */
object QQSettingDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isNightTheme(context: Context): Boolean = ThemeColors.isNightTheme(context)

    fun show(activity: Activity, engine: PetAdventureEngine?) {
        val context = activity
        var dialogInstance: Dialog? = null
        val isNight = isNightTheme(context)
        val colors = ThemeColors.get(isNight)
        val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)

        val fullRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val statusCardHelper = SettingStatusCard(context, colors, engine, fullRoot) { dialogInstance }
        fullRoot.addView(statusCardHelper.buildTopBar())
        fullRoot.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 14), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 36))
        }

        contentLayout.addView(statusCardHelper.buildStatusCard())
        val careerCard = SettingCareerCard(context, colors, prefs, engine)
        careerCard.build(contentLayout)
        SettingDailyCard(context, colors, prefs, engine).build(contentLayout)
        SettingActionCard(context, colors, engine) { statusCardHelper.refreshLiveStatus() }.build(contentLayout)
        SettingMoreCard(context, colors).build(contentLayout)

        val scrollView = ScrollView(context).apply {
            addView(contentLayout)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        fullRoot.addView(scrollView)

        dialogInstance = createFullScreenDialog(activity, colors, fullRoot)
        val tickerRunnable = object : Runnable {
            override fun run() {
                statusCardHelper.refreshLiveStatus()
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.post(tickerRunnable)
        dialogInstance.setOnDismissListener { mainHandler.removeCallbacks(tickerRunnable) }

        asyncSyncAccountData(context, engine, careerCard) { statusCardHelper.refreshLiveStatus() }

        dialogInstance.show()
        playSlideInAnimation(fullRoot, context)
    }

    private fun createFullScreenDialog(activity: Activity, colors: ThemeColors, fullRoot: View): Dialog {
        val themeRes = if (colors.isNight) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        val dialog = object : Dialog(activity, themeRes) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                UiAnimUtils.dismissWithAnimation(fullRoot, this)
            }
        }.apply {
            setContentView(fullRoot)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(colors.pageBg))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            attributes = attributes?.apply { dimAmount = 0f }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = colors.pageBg
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = if (colors.isNight) {
                    decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }
        return dialog
    }

    private fun playSlideInAnimation(fullRoot: View, context: Context) {
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        fullRoot.translationX = screenWidth
        fullRoot.animate()
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator(2.0f))
            .start()
    }

    private fun asyncSyncAccountData(
        context: Context,
        engine: PetAdventureEngine?,
        careerCard: SettingCareerCard,
        onUpdated: () -> Unit
    ) {
        val activeEngine = engine ?: HookEntry.globalEngine ?: return
        CoroutineScope(Dispatchers.IO).launch {
            activeEngine.verifyAndSyncAccountSession(context)
            val (_, remotePetId) = activeEngine.queryOwnPetAwait()
            val petId = if (!remotePetId.isNullOrEmpty() && PetAdventureEngine.shouldUpdateCachedPetId(PetAdventureEngine.cachedPetId, remotePetId)) {
                Log.w("QQSettingDialog", "🔄 [弹窗核验] 发现新活跃小宠 ID: $remotePetId，覆写旧缓存: ${PetAdventureEngine.cachedPetId}")
                PetAdventureEngine.saveScopedPetId(context, remotePetId)
                remotePetId
            } else {
                PetAdventureEngine.cachedPetId ?: remotePetId
            }
            if (!petId.isNullOrEmpty()) {
                val preloaded = activeEngine.preloadAccountDataAwait(petId)
                activeEngine.queryPetAttributesAwait(petId)
                mainHandler.post {
                    careerCard.updateSchoolUnlockStates(preloaded.schoolDetails)
                    careerCard.updateWorkUnlockStates(preloaded.workPlaces, preloaded.workJobs)
                    onUpdated()
                }
                if (PetAdventureEngine.loadCachedHireableFriends(context).isEmpty()) {
                    activeEngine.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
                }
            }
        }
    }
}
