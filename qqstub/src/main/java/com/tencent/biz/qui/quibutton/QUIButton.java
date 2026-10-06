package com.tencent.biz.qui.quibutton;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

/** 编译期桩：QQ QUI 按钮（跟随宿主换肤）。 */
public class QUIButton extends Button {

    public QUIButton(Context context) {
        super(context);
    }

    public QUIButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QUIButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setBackgroundPressed() {
    }

    public void setBackgroundDisabled() {
    }
}
