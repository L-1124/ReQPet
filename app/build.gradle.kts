plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.copilot.qqpet"
    compileSdk = 34

   defaultConfig {
       // LSPosed 官方模块仓库要求包名归属可验证：io.github.<username> 前缀或自有域名反写
       applicationId = "io.github.congsmile.qqpet"
       minSdk = 26
       targetSdk = 34
       versionCode = 90
       // 版本递增规范：每次更新在末位增加 0.0.1 (1.0.0 -> 1.0.1 -> 1.0.2 -> ... -> 1.0.87)
       versionName = "1.0.89"
   }

   buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("androidx.annotation:annotation:1.5.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
