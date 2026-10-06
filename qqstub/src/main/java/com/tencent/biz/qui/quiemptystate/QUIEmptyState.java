package com.tencent.biz.qui.quiemptystate;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.tencent.biz.qui.quibutton.QUIButton;

/** 编译期桩：QQ QUI 空状态视图。 */
public class QUIEmptyState extends LinearLayout {

    public QUIEmptyState(Context context, Builder builder) {
        super(context);
    }

    public void setTitle(String title) {
    }

    public void setDesc(String desc) {
    }

    public void setImageView(int imageType) {
    }

    public void setBtnText(String text, int style) {
    }

    public void setBtnText(String text, int style, View.OnClickListener listener) {
    }

    public QUIButton getButton() {
        return null;
    }

    /** 真实签名即 `QUIEmptyState$Builder`。 */
    public static class Builder {

        public Builder(Context context) {
        }

        public Builder setTitle(String title) {
            return this;
        }

        public Builder setDesc(String desc) {
            return this;
        }

        public Builder setButton(String text, View.OnClickListener listener) {
            return this;
        }

        public Builder setImageType(int imageType) {
            return this;
        }

        public Builder setThemeType(int themeType) {
            return this;
        }

        public Builder setAutoCenter(boolean autoCenter) {
            return this;
        }

        public Builder setUseRawHeight(boolean useRawHeight) {
            return this;
        }

        public Builder setHalfScreenState(boolean halfScreen) {
            return this;
        }

        public QUIEmptyState build() {
            return null;
        }
    }
}
