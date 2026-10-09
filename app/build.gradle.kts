plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.reqpet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // LSPosed 官方模块仓库要求包名归属可验证：io.github.<username> 前缀或自有域名反写
        applicationId = "io.github.reqpet"
        minSdk = 26
        targetSdk = 37
        versionCode = 114
        versionName = "1.0.113"
    }

    buildTypes {
        release {
            optimization {
                enable = true
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
    // AGP 内置 Kotlin 版本与 Compose 插件不同，映射工具必须跟随 Compose 插件版本。
    add("composeMappingProducerClasspath", "org.jetbrains.kotlin:compose-group-mapping:${libs.versions.kotlin.get()}")

    compileOnly(libs.libxposed.api)
    implementation(libs.androidx.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)
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