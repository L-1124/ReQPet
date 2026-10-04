package com.copilot.qqpet.ui.section

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.ui.dialog.SettingConfirmDialog
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.CardUiBuilder
import com.copilot.qqpet.ui.util.SettingConfigSyncer
import com.copilot.qqpet.ui.util.UiAnimUtils

class SettingActionCard(
    private val context: Context,
    private val colors: ThemeColors,
    private val engine: PetAdventureEngine?,
    private val onActionTriggered: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class ActionItemDef(
        val title: String,
        val confirmTitle: String,
        val confirmMessage: String,
        val confirmBtnText: String,
        val actionCmd: String,
        val colorHex: String = "#1C1C1E",
        val isBold: Boolean = false,
        val isDestructive: Boolean = false
    )

    fun build(container: LinearLayout): View {
        CardUiBuilder.addSectionHeader(container, "手动即时指令", colors)
        val card = CardUiBuilder.createGroupCard(context, colors)

        val actions = listOf(
            ActionItemDef("立即执行全套巡检与养成", "执行全套巡检与养成？", "将立即同步小宠最新资质与起居状态，按需触发进食洗澡，并依序规划自适应日程。", "立即执行", "cycle", colorHex = "#007AFF", isBold = true),
            ActionItemDef("立即派遣打工", "立即派遣打工？", "将根据设定的打工场所与时长偏好，立即为小宠开启新一轮勤劳打工。", "立即打工", "work"),
            ActionItemDef("立即启程学习", "立即启程学习？", "将根据设定的学府与专攻科目偏好，立即为小宠安排官方课程研修。", "立即学习", "school"),
            ActionItemDef("立即野外探险", "立即野外探险？", "将立即启程前往神秘森林，开启野外探秘与修行历练。", "立即探险", "adventure"),
            ActionItemDef("立即结算探险收益", "结算探险收益？", "将立即向服务端请求结算当前探险掉落，领回所有金币、经验与道具奖励。", "立即结算", "settle"),
            ActionItemDef("立即回踩访客 (互相踩踩)", "立即回踩访客？", "将拉取最近造访小家的记录，并依次向未回赠的好友与陌生访客发起回踩送心。", "立即回礼", "like_back", colorHex = "#007AFF"),
            ActionItemDef("立即主动串门踩踩 (好友+随机陌生人)", "立即主动串门踩踩？", "将自动筛选今日尚未踩过的好友与随机陌生小宠，保持拟人离散间隔主动串门送心。", "立即串门", "active_visit", colorHex = "#007AFF"),
            ActionItemDef("立即领取金币福袋", "立即领取金币福袋？", "将立即扫描自己小窝及全部好友小窝，发现掉落福袋时自动拆袋领取金币奖励。", "立即拆福袋", "coinbag", colorHex = "#007AFF"),
            ActionItemDef("立即帮全部好友喂食与洗澡", "立即帮好友宠物喂食洗澡？", "将立即检测全部养宠好友的实时体力与清洁度，低于设定阈值时自动帮好友喂食与搓澡。", "立即照料好友", "friend_care", colorHex = "#007AFF"),
            ActionItemDef("立即自动 PK 挑战 (实测10场对决)", "确认发起自动 PK 对决？", "将自动筛选三维属性低于我方的对手（包含好友与访客陌生人），每次随机休眠1~3分钟，连打10场自动领奖。", "立即对决", "pk_auto", colorHex = "#FF9500"),
            ActionItemDef("立即召回宠物回家 (中断当前打工/学习)", "确认召回宠物回家？", "此操作将强制中断小宠当前正在进行的打工或学习派遣，提前返程回家。", "确认召回", "recall", colorHex = "#FF3B30", isBold = true, isDestructive = true)
        )

        actions.forEachIndexed { index, def ->
            val isLast = index == actions.lastIndex
            addActionItem(card, def, isLast)
        }

        container.addView(card)
        return card
    }

    private fun addActionItem(card: LinearLayout, def: ActionItemDef, isLast: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(context, 14), 0, UiAnimUtils.dp(context, 14))
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                SettingConfirmDialog.showConfirmDialog(
                    context = context, colors = colors, title = def.confirmTitle,
                    message = def.confirmMessage, confirmText = def.confirmBtnText,
                    isDestructive = def.isDestructive
                ) {
                    SettingConfigSyncer.triggerAction(context, engine, def.actionCmd)
                    mainHandler.postDelayed({ onActionTriggered() }, 800L)
                }
            }
        }
        val resolvedColor = when (def.colorHex) {
            "#007AFF" -> colors.actionBlueText
            "#FF3B30" -> colors.actionRedText
            else -> colors.actionPrimaryText
        }
        val tView = TextView(context).apply {
            text = def.title; textSize = 15.5f
            typeface = if (def.isBold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(resolvedColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tView)
        card.addView(row)
        if (!isLast) {
            card.addView(CardUiBuilder.createDivider(context, colors))
        }
    }
}
