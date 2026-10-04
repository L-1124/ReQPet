package com.copilot.qqpet.ui.section

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.UiAnimUtils

class SettingStatusCard(
    private val context: Context,
    private val colors: ThemeColors,
    private val engine: PetAdventureEngine?,
    private val fullRoot: View,
    private val dialogProvider: () -> Dialog?
) {

    private lateinit var statusActionText: TextView
    private lateinit var statusAttributesText: TextView

    fun buildTopBar(): View {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.pageBg)
            setPadding(UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 10))
        }

        val backBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 4))
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                UiAnimUtils.dismissWithAnimation(fullRoot, dialogProvider())
            }
        }
        backBtn.addView(TextView(context).apply {
            text = "‹"
            textSize = 24f
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            setTextColor(if (colors.isNight) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF"))
            setPadding(0, 0, UiAnimUtils.dp(context, 2), UiAnimUtils.dp(context, 2))
        })
        backBtn.addView(TextView(context).apply {
            text = "设置"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (colors.isNight) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF"))
        })
        topBar.addView(backBtn)

        val navTitle = TextView(context).apply {
            text = "Q宠后台伴侣"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        topBar.addView(navTitle)

        val statusPill = TextView(context).apply {
            text = "● 运行中"
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.badgeText)
            setPadding(UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 3), UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 3))
            background = GradientDrawable().apply {
                setColor(this@SettingStatusCard.colors.badgeBg)
                cornerRadius = UiAnimUtils.dp(context, 10).toFloat()
            }
        }
        topBar.addView(statusPill)
        return topBar
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
