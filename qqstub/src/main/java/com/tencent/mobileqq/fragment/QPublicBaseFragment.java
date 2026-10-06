package com.tencent.mobileqq.fragment;

import android.app.Activity;

/**
 * 编译期桩类：仅让模块代码能够继承宿主的设置页基类。
 *
 * 运行时由宿主 APK 提供真实实现（模块 classloader 以宿主 loader 为父），
 * 本桩类通过 compileOnly 引入，绝不会进入模块 APK。
 */
public class QPublicBaseFragment extends androidx.fragment.app.Fragment {

    /** 宿主 BasePartFragment 的抽象方法，真实实现位于宿主 QBaseFragment */
    protected int getContentLayoutId() {
        return 0;
    }

    /** 宿主提供的窗口样式扩展点（状态栏/转场动画） */
    public void initWindowStyleAndAnimation(Activity activity) {
    }

    public boolean needImmersive() {
        return false;
    }

    public boolean needStatusTrans() {
        return true;
    }
}
