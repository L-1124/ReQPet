# libxposed 模块入口（框架通过反射实例化 java_init.list 中的类）
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void on*(...);
}
-keep class com.copilot.qqpet.HookEntry { *; }
-dontwarn io.github.libxposed.api.**