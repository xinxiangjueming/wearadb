plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    kotlin("kapt")
}

android {
    namespace = "com.wearadb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wearadb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ADB 协议库：改用本地化模块（libadb-android/，源码 = 上游 3.1.1 + generateOpen 修复）。
    // 原 Maven 依赖 com.github.MuntashirAkon:libadb-android:3.1.1 的 AdbProtocol.generateOpen()
    // 按字符数分配 A_OPEN 缓冲区却写入 UTF-8 字节 → 命令含非 ASCII（中文文件名/路径）时
    // 在发包前抛 BufferOverflowException（上游 issue #25，3.1.1 未修）。
    implementation(project(":libadb-android"))

    // Conscrypt (独立版, libadb-android 配对需要 exportKeyingMaterial API)
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // BouncyCastle (证书生成 + 加密)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

kapt {
    correctErrorTypes = true
}
