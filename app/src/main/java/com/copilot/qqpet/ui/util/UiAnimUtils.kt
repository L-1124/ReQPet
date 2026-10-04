package com.copilot.qqpet.ui.util

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

object UiAnimUtils {

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun dpF(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }

    fun dismissWithAnimation(rootView: View, dialog: Dialog?) {
        val screenWidth = rootView.context.resources.displayMetrics.widthPixels.toFloat()
        rootView.animate()
            .translationX(screenWidth)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2.0f))
            .withEndAction {
                try {
                    dialog?.dismiss()
                } catch (_: Throwable) {}
            }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun applyTouchSpringEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator(2.0f)).start()
                }
            }
            false
        }
    }

    fun animateExpandCollapse(view: View, expand: Boolean) {
        if (expand) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.measure(
                View.MeasureSpec.makeMeasureSpec(view.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = if (view.measuredHeight > 0) view.measuredHeight else dp(view.context, 160)
            view.layoutParams.height = 0
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { va ->
                    val f = va.animatedValue as Float
                    view.layoutParams.height = (targetHeight * f).toInt()
                    view.alpha = f
                    view.requestLayout()
                    if (f >= 1.0f) {
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            anim.start()
        } else {
            val startHeight = view.height
            val anim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 200
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { va ->
                    val f = va.animatedValue as Float
                    view.layoutParams.height = (startHeight * f).toInt()
                    view.alpha = f
                    view.requestLayout()
                    if (f <= 0f) {
                        view.visibility = View.GONE
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            anim.start()
        }
    }
}
