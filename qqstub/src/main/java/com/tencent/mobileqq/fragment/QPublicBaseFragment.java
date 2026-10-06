package com.tencent.mobileqq.fragment;

import android.app.Activity;

/** 编译期桩：运行时由宿主 APK 提供真实实现；经 compileOnly 引入，不会打包进 APK。 */
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
