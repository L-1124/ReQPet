package com.copilot.qqpet.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.ui.component.AppleSwitchView
import com.copilot.qqpet.ui.theme.ThemeColors

object CardUiBuilder {

    fun createGroupCard(context: Context, colors: ThemeColors): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colors.cardBg)
                cornerRadius = UiAnimUtils.dp(context, 12).toFloat()
                if (colors.isNight) {
                    setStroke(1, colors.cardBorder)
                }
            }
            setPadding(UiAnimUtils.dp(context, 16), 0, UiAnimUtils.dp(context, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, UiAnimUtils.dp(context, 18))
            }
        }
    }

    fun createDivider(context: Context, colors: ThemeColors): View {
        return View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
    }

    fun addSectionHeader(container: LinearLayout, title: String, colors: ThemeColors) {
        val context = container.context
        val hView = TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colors.sectionHeaderText)
            setPadding(UiAnimUtils.dp(context, 4), 0, 0, UiAnimUtils.dp(context, 7))
        }
        container.addView(hView)
    }

    fun addSimpleToggleRow(
        card: LinearLayout,
        context: Context,
        colors: ThemeColors,
        prefs: SharedPreferences,
        engine: PetAdventureEngine?,
        title: String,
        desc: String,
        prefKey: String,
        defaultVal: Boolean,
        isLast: Boolean
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(context, 12), 0, UiAnimUtils.dp(context, 12))
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, UiAnimUtils.dp(context, 10), 0)
            }
        }
        val tView = TextView(context).apply {
            text = title
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
        }
        val dView = TextView(context).apply {
            text = desc
            textSize = 13f
            setTextColor(colors.secondaryText)
            setPadding(0, UiAnimUtils.dp(context, 2), 0, 0)
        }
        textCol.addView(tView)
        textCol.addView(dView)
        row.addView(textCol)

        val initialChecked = prefs.getBoolean(prefKey, defaultVal)
        val sw = AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(initialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean(prefKey, isChecked).commit()
                SettingConfigSyncer.syncConfig(engine, context)
            }
        }
        row.addView(sw)
        card.addView(row)
        if (!isLast) {
            card.addView(createDivider(context, colors))
        }
    }
}
