package com.tencent.biz.qui.quisecnavbar;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.RelativeLayout;

/**
 * 编译期桩类：腾讯 QUI 的二级页导航栏（`com.tencent.biz.qui.quisecnavbar.QUISecNavBar`）。
 *
 * QQ 9.3.70 中的真实行为（反编译确认）：
 * - 构造器可在代码中直接用，无需宿主布局；
 * - [w] 会把宿主窗口状态栏设为透明，并把状态栏高度加到自己根布局的 padding 上
 *   （内部使用 RFWImmersiveUtils），因此调用方不必自己处理状态栏；
 * - setLeftType/setCenterType/setRightType：0=隐藏、1=文字、左/右 2 或 3=图标。
 *
 * 本桩类仅用于编译，不会打包进模块 APK。
 */
public class QUISecNavBar extends RelativeLayout {

    public QUISecNavBar(Context context) {
        super(context);
    }

    public void setLeftType(int type) {
    }

    public void setCenterType(int type) {
    }

    public void setRightType(int type) {
    }

    public void setLeftText(CharSequence text) {
    }

    public void setCenterText(CharSequence text) {
    }

    public void setRightText(CharSequence text) {
    }

    public void setBaseClickListener(BaseAction action, View.OnClickListener listener) {
    }

    public void setBaseViewDescription(BaseAction action, String description) {
    }

    public void setBaseViewVisible(BaseAction action, boolean visible) {
    }

    /** 绑定宿主窗口：透明状态栏 + 自身让出状态栏高度 */
    public void w(Activity activity) {
    }
}
