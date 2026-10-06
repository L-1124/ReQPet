package com.copilot.qqpet.ui.section

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.UiAnimUtils
import com.tencent.biz.qui.quisecnavbar.BaseAction
import com.tencent.biz.qui.quisecnavbar.QUISecNavBar

class SettingStatusCard(
    private val context: Context,
    private val colors: ThemeColors,
    private val engine: PetAdventureEngine?,
    private val onBack: () -> Unit
) {

    private companion object {
        /** QUISecNavBar 的 setXxxType 语义：0=隐藏、1=文字、左/右 2 或 3=图标 */
        const val NAV_LEFT_ICON = 2
        const val NAV_CENTER_TEXT = 1
    }

    private lateinit var statusActionText: TextView
    private lateinit var statusAttributesText: TextView

    /** 顶栏用宿主 QUI 组件 [QUISecNavBar]（与 QQ 设置页同款） */
    fun buildTopBar(): View {
        val navBar = QUISecNavBar(context)
        navBar.setLeftType(NAV_LEFT_ICON)
        navBar.setCenterType(NAV_CENTER_TEXT)
        navBar.setCenterText("Q宠后台伴侣")
        navBar.setBaseViewDescription(BaseAction.ACTION_LEFT_BUTTON, "返回")
        navBar.setBaseClickListener(BaseAction.ACTION_LEFT_BUTTON, View.OnClickListener { onBack() })
        if (context is Activity) applyStatusBarInset(context, navBar)
        return navBar
    }

    /** 宿主 QUISecNavBar 里做这事的 `w(Activity)` 是混淆名，不能依赖，故自行处理状态栏 */
    private fun applyStatusBarInset(activity: Activity, navBar: View) {
        activity.window?.apply {
            statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = if (colors.isNight) {
                    decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val inset = if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
        if (inset > 0) {
            navBar.setPadding(navBar.paddingLeft, navBar.paddingTop + inset, navBar.paddingRight, navBar.paddingBottom)
        }
    }

    fun buildStatusCard(): View {
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(this@SettingStatusCard.colors.cardBg)
                cornerRadius = UiAnimUtils.dp(context, 12).toFloat()
                if (this@SettingStatusCard.colors.isNight) setStroke(1, this@SettingStatusCard.colors.cardBorder)
            }
            setPadding(UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 14), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, UiAnimUtils.dp(context, 16))
            }
        }

        statusActionText = TextView(context).apply {
            text = PetAdventureEngine.formatLiveStatusText()
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
        }
        (engine ?: HookEntry.globalEngine)?.verifyAndSyncAccountSession(context)
        statusAttributesText = TextView(context).apply {
            val d = PetAdventureEngine.cachedSchoolDetails
            val petId = PetAdventureEngine.cachedPetId
            val attrs = if (!petId.isNullOrEmpty()) {
                QQPetDirectBridge.cachedPetAttributes ?: HookEntry.globalBridge?.getPetAttributes(petId)
            } else null
            val attrPrefix = if (d != null && d.code == 0) "小宠资质 · 力量 ${d.power}  智力 ${d.intel}  魅力 ${d.charm}" else "小宠资质 · 实时同步官方属性中"
            val liveCare = if (attrs != null && attrs.energy >= 0f) " · 体力 ${attrs.energy.toInt()} 清洁 ${attrs.clean.toInt()}" else ""
            text = "$attrPrefix$liveCare"
            textSize = 13f
            setTextColor(colors.secondaryText)
            setPadding(0, UiAnimUtils.dp(context, 4), 0, 0)
        }
        statusCard.addView(statusActionText)
        statusCard.addView(statusAttributesText)
        return statusCard
    }

    fun refreshLiveStatus() {
        if (!::statusActionText.isInitialized || !::statusAttributesText.isInitialized) return
        statusActionText.text = PetAdventureEngine.formatLiveStatusText()
        val d = PetAdventureEngine.cachedSchoolDetails
        val petId = PetAdventureEngine.cachedPetId
        val attrs = if (!petId.isNullOrEmpty()) {
            QQPetDirectBridge.cachedPetAttributes ?: HookEntry.globalBridge?.getPetAttributes(petId)
        } else null
        val attrPrefix = if (d != null && d.code == 0) "小宠资质 · 力量 ${d.power}  智力 ${d.intel}  魅力 ${d.charm}" else "小宠资质 · 实时同步官方属性中"
        val liveCare = if (attrs != null && attrs.energy >= 0f) " · 体力 ${attrs.energy.toInt()} 清洁 ${attrs.clean.toInt()}" else ""
        statusAttributesText.text = "$attrPrefix$liveCare"
    }
}
