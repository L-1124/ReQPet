package com.copilot.qqpet.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

data class SegmentItem(
    val title: String,
    val enabled: Boolean = true,
    val disabledTip: String? = null
)

data class WorkPlaceOption(
    val careerId: Int,
    val title: String,
    val enabled: Boolean = true,
    val disabledTip: String? = null
)

/**
 * 纯文字自绘制的 iOS 标准 Segmented Control (分段药丸选择器)
 * 支持平滑横向滚动与动态可用性置灰
 */
class AppleSegmentedControl(
    context: Context,
    initialItems: List<Any>,
    private var selectedIndex: Int = 0,
    private val isScrollable: Boolean = false,
    private val isNight: Boolean = false,
    private val onItemSelected: (Int) -> Unit
) : LinearLayout(context) {

    private val textViews = mutableListOf<TextView>()
    private val itemStates = mutableListOf<SegmentItem>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(if (isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#EBEBED"))
            cornerRadius = dp(8f)
        }
        setPadding(dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt())

        val normalizedItems = initialItems.map {
            when (it) {
                is SegmentItem -> it
                is String -> SegmentItem(it, enabled = true)
                else -> SegmentItem(it.toString(), enabled = true)
            }
        }
        rebuildViews(normalizedItems, selectedIndex)
    }

    private fun rebuildViews(newItems: List<SegmentItem>, newSelected: Int) {
        removeAllViews()
        textViews.clear()
        itemStates.clear()
        selectedIndex = if (newSelected in newItems.indices) newSelected else 0
        newItems.forEachIndexed { index, item ->
            itemStates.add(item)
            val tv = TextView(context).apply {
                text = item.title
                textSize = if (newItems.size >= 5 && !isScrollable) 11f else 12f
                maxLines = 1
                gravity = Gravity.CENTER
                if (isScrollable) {
                    setPadding(dp(11f).toInt(), dp(6.5f).toInt(), dp(11f).toInt(), dp(6.5f).toInt())
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                } else {
                    setPadding(dp(2f).toInt(), dp(6.5f).toInt(), dp(2f).toInt(), dp(6.5f).toInt())
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
                }
                alpha = if (item.enabled) 1.0f else 0.35f
                setOnClickListener {
                    val state = itemStates.getOrNull(index)
                    if (state != null && !state.enabled) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val tip = state.disabledTip ?: "该选项尚未解锁"
                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (selectedIndex != index) {
                        selectedIndex = index
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        updateSelection()
                        onItemSelected(index)
                        playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    }
                }
            }
            textViews.add(tv)
            addView(tv)
        }
        updateSelection()
    }

    fun rebuildItems(newItems: List<SegmentItem>, newSelectedIndex: Int = 0) {
        rebuildViews(newItems, newSelectedIndex)
    }

    fun setSelection(index: Int) {
        if (index in itemStates.indices && index != selectedIndex) {
            selectedIndex = index
            updateSelection()
        }
    }

    fun setControlEnabled(enabled: Boolean) {
        alpha = if (enabled) 1.0f else 0.38f
        isEnabled = enabled
        textViews.forEach { it.isEnabled = enabled }
    }

    fun updateItemStates(newStates: List<SegmentItem>) {
        if (newStates.size != itemStates.size) {
            rebuildViews(newStates, selectedIndex)
            return
        }
        for (i in newStates.indices) {
            itemStates[i] = newStates[i]
            val tv = textViews.getOrNull(i) ?: continue
            tv.text = newStates[i].title
            tv.alpha = if (newStates[i].enabled) 1.0f else 0.35f
        }
    }

    private fun updateSelection() {
        for (i in textViews.indices) {
            val tv = textViews[i]
            val isSel = (i == selectedIndex)
            if (isSel) {
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                tv.setTextColor(if (isNight) Color.WHITE else Color.parseColor("#1C1C1E"))
                tv.background = GradientDrawable().apply {
                    setColor(if (isNight) Color.parseColor("#636366") else Color.WHITE)
                    cornerRadius = dp(6.5f)
                    if (!isNight) {
                        setStroke(dp(0.5f).toInt(), Color.parseColor("#15000000"))
                    }
                }
            } else {
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tv.setTextColor(Color.parseColor("#8E8E93"))
                tv.background = null
            }
        }
        if (isScrollable) {
            post {
                val selTv = textViews.getOrNull(selectedIndex)
                if (selTv != null) {
                    (parent as? HorizontalScrollView)?.smoothScrollTo((selTv.left - dp(24f)).toInt().coerceAtLeast(0), 0)
                }
            }
        }
    }

    private fun dp(v: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            context.resources.displayMetrics
        )
    }
}
