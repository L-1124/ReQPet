# libxposed 模块入口
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void on*(...);
}
-keep class io.github.reqpet.HookEntry {
    public <init>(...);
    public void on*(...);
}

# libxposed 框架依赖
-dontwarn io.github.libxposed.api.**

# 设置页导航与状态恢复枚举
-keepclassmembers enum io.github.reqpet.ui.compose.SettingsPage {
    *;
}