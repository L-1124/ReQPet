import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class GenerateDebugVersionName : DefaultTask() {
    @get:Input
    abstract val baseVersion: Property<String>

    @get:Input
    abstract val gitHash: Property<String>

    @get:Input
    abstract val gitStatus: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val dirty = if (gitStatus.get().isNotBlank()) ".dirty" else ""
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("${baseVersion.get()}-dev.g${gitHash.get()}$dirty.$timestamp")
    }
}

val releaseVersionCode = providers.gradleProperty("versionCode").map(String::toInt).orElse(1)
val releaseVersionName = providers.gradleProperty("versionName").orElse("1.0.0")
val environmentKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
val localSigningProperties = Properties().apply {
    if (environmentKeystorePath == null) {
        val configFile = rootProject.file("keystore.properties")
        if (configFile.isFile) configFile.inputStream().use { load(it) }
    }
}
val releaseKeystorePath = environmentKeystorePath ?: localSigningProperties.getProperty("storeFile")

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
        versionCode = releaseVersionCode.get()
        versionName = releaseVersionName.get()
    }

    if (releaseKeystorePath != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseKeystorePath)
            storePassword = if (environmentKeystorePath != null) {
                providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
            } else {
                requireNotNull(localSigningProperties.getProperty("storePassword")) { "Missing storePassword in keystore.properties" }
            }
            keyAlias = if (environmentKeystorePath != null) {
                providers.environmentVariable("RELEASE_KEY_ALIAS").get()
            } else {
                requireNotNull(localSigningProperties.getProperty("keyAlias")) { "Missing keyAlias in keystore.properties" }
            }
            keyPassword = if (environmentKeystorePath != null) {
                providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            } else {
                requireNotNull(localSigningProperties.getProperty("keyPassword")) { "Missing keyPassword in keystore.properties" }
            }
        }
    }

    buildTypes {
        debug {
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName(if (releaseKeystorePath != null) "release" else "debug")
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

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val versionTask = tasks.register<GenerateDebugVersionName>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}VersionName"
        ) {
            baseVersion.set(releaseVersionName)
            gitHash.set(providers.exec {
                workingDir(rootDir)
                commandLine("git", "rev-parse", "--short=7", "HEAD")
            }.standardOutput.asText.map { it.trim() })
            gitStatus.set(providers.exec {
                workingDir(rootDir)
                commandLine("git", "status", "--porcelain")
            }.standardOutput.asText)
            outputFile.set(layout.buildDirectory.file("generated/version/${variant.name}/versionName.txt"))
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Debug builds carry an execution-time timestamp") { true }
        }
        variant.outputs.forEach { output ->
            output.versionName.set(versionTask.flatMap { it.outputFile }.map { it.asFile.readText() })
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