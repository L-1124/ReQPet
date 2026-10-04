package com.copilot.qqpet.ui.component

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 苹果原生交互质感的高精度平滑动画开关 (iOS 风格自定义 Switch)
 */
@SuppressLint("ClickableViewAccessibility")
class AppleSwitchView(context: Context, private val isNight: Boolean = false) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(2f), 0f, dp(1f), Color.parseColor("#25000000"))
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12000000")
    }

    private val rect = RectF()
    private var progress = 1.0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                animateTo(if (value) 1.0f else 0.0f)
            }
        }

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    fun setCheckedImmediately(checked: Boolean) {
        isChecked = checked
        progress = if (checked) 1.0f else 0.0f
        invalidate()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                progress = va.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = dp(51f).toInt()
        val h = dp(31f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)

        val offR = if (isNight) 0x39 else 0xE9
        val offG = if (isNight) 0x39 else 0xE9
        val offB = if (isNight) 0x3D else 0xEB
        val onR = if (isNight) 0x30 else 0x34
        val onG = if (isNight) 0xD1 else 0xC7
        val onB = if (isNight) 0x58 else 0x59

        val curR = (offR + (onR - offR) * progress).toInt()
        val curG = (offG + (onG - offG) * progress).toInt()
        val curB = (offB + (onB - offB) * progress).toInt()

        bgPaint.color = Color.rgb(curR, curG, curB)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        val pad = dp(2f)
        val thumbRadius = (h - pad * 2) / 2f
        val startX = pad + thumbRadius
        val endX = w - pad - thumbRadius
        val thumbX = startX + (endX - startX) * progress
        val thumbY = h / 2f

        canvas.drawCircle(thumbX, thumbY + dp(0.8f), thumbRadius, shadowPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            isChecked = !isChecked
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onCheckedChangeListener?.invoke(isChecked)
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
        }
        return true
    }

    private fun dp(v: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            context.resources.displayMetrics
        )
    }
}
