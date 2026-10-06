plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // META-INF/xposed/* 是 libxposed 的模块元数据，必须合并进 APK
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    compileOnly(project(":qqstub"))
    // 运行期的 androidx.fragment 由宿主提供，这里只需编译期可见
    compileOnly(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)

    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}