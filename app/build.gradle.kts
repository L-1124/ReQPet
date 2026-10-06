plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.copilot.qqpet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // LSPosed 官方模块仓库要求包名归属可验证：io.github.<username> 前缀或自有域名反写
        applicationId = "io.github.congsmile.qqpet"
        minSdk = 26
        targetSdk = 37
        versionCode = 114
        versionName = "1.0.113"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly(libs.libxposed.api)
    compileOnly(project(":qqstub"))
    compileOnly(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}