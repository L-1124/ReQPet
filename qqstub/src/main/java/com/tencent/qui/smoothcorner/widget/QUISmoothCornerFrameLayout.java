package com.tencent.qui.smoothcorner.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 编译期桩：QQ 平滑圆角容器（G2 连续曲率圆角）。 */
public class QUISmoothCornerFrameLayout extends FrameLayout {

    public QUISmoothCornerFrameLayout(Context context) {
        super(context);
    }

    public QUISmoothCornerFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setCornerRadius(float radius) {
    }
}
