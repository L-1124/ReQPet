package com.copilot.qqpet.ui.section

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.CardUiBuilder
import com.copilot.qqpet.ui.util.UiAnimUtils

/** 运行日志面板（注入在 QQ 设置页内）：订阅 [EngineLog]，由宿主弹窗 attach / detach。 */
class SettingLogCard(
    private val context: Context,
    private val colors: ThemeColors
) {
    private companion object {
        const val MAX_RENDER_LINES = 80
        const val RENDER_THROTTLE_MS = 300L
        const val PLACEHOLDER = "暂无日志"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener: (String) -> Unit = { scheduleRender() }

    private var logView: TextView? = null
    private var scrollView: ScrollView? = null
    private var summaryView: TextView? = null
    private var renderScheduled = false
    private var attached = false

    private val renderRunnable = Runnable { render() }

    fun build(container: LinearLayout) {
        // 局部变量：GradientDrawable 自带 colors 属性，会遮蔽类属性
        val themeColors = colors
        val ctx = context

        CardUiBuilder.addSectionHeader(container, "运行日志", themeColors)
        val card = CardUiBuilder.createGroupCard(ctx, themeColors)

        val console = TextView(ctx).apply {
            text = PLACEHOLDER
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(themeColors.primaryText)
            setPadding(
                UiAnimUtils.dp(ctx, 10), UiAnimUtils.dp(ctx, 8),
                UiAnimUtils.dp(ctx, 10), UiAnimUtils.dp(ctx, 8)
            )
            setLineSpacing(UiAnimUtils.dpF(ctx, 2f), 1f)
        }
        logView = console

        val consoleBg = themeColors.pageBg
        val consoleRadius = UiAnimUtils.dpF(ctx, 8f)
        scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = GradientDrawable().apply {
                setColor(consoleBg)
                cornerRadius = consoleRadius
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiAnimUtils.dp(ctx, 132)
            ).apply { setMargins(0, UiAnimUtils.dp(ctx, 12), 0, 0) }
            addView(console)
        }
        card.addView(scrollView)

        summaryView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(themeColors.secondaryText)
            setPadding(0, UiAnimUtils.dp(ctx, 8), 0, UiAnimUtils.dp(ctx, 10))
        }
        card.addView(summaryView)
        card.addView(CardUiBuilder.createDivider(ctx, themeColors))

        val clearRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(ctx, 12), 0, UiAnimUtils.dp(ctx, 12))
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                EngineLog.clear()
                render()
            }
        }
        clearRow.addView(TextView(ctx).apply {
            text = "清空日志"
            textSize = 15f
            setTextColor(themeColors.actionRedText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        })
        clearRow.addView(TextView(ctx).apply {
            text = "🧹"
            textSize = 16f
            setTextColor(themeColors.secondaryText)
        })
        card.addView(clearRow)

        container.addView(card)
        refreshSummary()
    }

    fun attach() {
        if (attached) return
        attached = true
        EngineLog.addListener(listener)
        render()
    }

    fun detach() {
        if (!attached) return
        attached = false
        EngineLog.removeListener(listener)
        mainHandler.removeCallbacks(renderRunnable)
        renderScheduled = false
        logView = null
        scrollView = null
        summaryView = null
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        mainHandler.postDelayed(renderRunnable, RENDER_THROTTLE_MS)
    }

    private fun render() {
        renderScheduled = false
        val view = logView ?: return
        val lines = EngineLog.snapshot()
        view.text = if (lines.isEmpty()) PLACEHOLDER else lines.takeLast(MAX_RENDER_LINES).joinToString("\n")
        refreshSummary(lines.size)
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshSummary(total: Int = EngineLog.snapshot().size) {
        summaryView?.text = "共 $total 条 · 上限 ${EngineLog.CAPACITY} 条"
    }
}
