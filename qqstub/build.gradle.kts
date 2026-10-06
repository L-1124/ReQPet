plugins {
    id("com.android.library")
}

android {
    namespace = "com.copilot.qqstub"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 仅供模块编译期引用宿主类型；不得打包进 APK（否则与宿主真实类形成两个 Class）
dependencies {
    compileOnly("androidx.appcompat:appcompat:1.6.1")
}
