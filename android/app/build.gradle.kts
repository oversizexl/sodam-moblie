import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.sodahub.sodam"
    compileSdk = 36

    val envVersionName = System.getenv("SODAM_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }
    val envVersionCode = System.getenv("SODAM_VERSION_CODE")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    defaultConfig {
        applicationId = "org.sodahub.sodam"
        minSdk = 26
        targetSdk = 36
        versionCode = envVersionCode ?: 1
        versionName = envVersionName ?: "0.1.0"

        ndk {
            // 手机先只出 arm64-v8a。模拟器需再跑 scripts/android-ffi.sh x86_64，
            // 并把这里的 "x86_64" 一起加进来。
            abiFilters += "arm64-v8a"
        }
    }

    // 发布签名从环境变量读取（CI secrets）；未配置时 release 退回 debug 签名，保证产物可安装。
    val releaseStore = System.getenv("SODAM_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("SODAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SODAM_KEY_ALIAS")
                keyPassword = System.getenv("SODAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseStore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // JNA 与 UniFFI 都通过 native 加载 .so；旧式解包在各类设备上最稳。
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    // 少量标准图标（刷新 / 更多），避免自绘。
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // @aar 变体携带各 ABI 的 libjna.so —— UniFFI 生成的 Kotlin 绑定依赖它。
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // 播放本地解密文件；media3-session 负责后台播放与通知栏/锁屏控制。
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    // 封面加载。Coil 2 自带 OkHttp 网络层，且不会把 Compose 拉到 1.12（AGP 8.13 不支持）。
    implementation("io.coil-kt:coil-compose:2.7.0")
}
