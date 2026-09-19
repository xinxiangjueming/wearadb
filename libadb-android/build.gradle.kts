// 本地化（vendored）的 libadb-android 3.1.1 源码模块。
//
// 目的：修掉上游 AdbProtocol.generateOpen() 的缓冲区长度缺陷（见 AdbProtocol.java 内的 PATCH 注释）。
// 该缺陷会让「命令里含任一非 ASCII 字符」（例如中文文件名/路径）时在**发包之前**抛
// BufferOverflowException —— 命令根本没发到设备，调用方拿到的却只是一个空字符串。
// 上游 issue #25 至今 Open，最新 tag 仍是 3.1.1，故在本地模块内根治。
//
// 除该处 patch 外，源码与上游 tag 3.1.1 的 sources jar 逐字一致（保留原 SPDX 头，
// 双许可 GPL-3.0-or-later OR Apache-2.0）。
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")

    // Spake2：ADB 配对（PairingAuthCtx / PairingConnectionCtx）
    implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1")

    // PairingAuthCtx 用到 org.bouncycastle.crypto.*（HKDF / GCM / SHA256Digest）
    // 注意：**不要**引 bcprov-jdk15to18（原 AAR 的传递依赖），它与 app 侧
    // bcpkix-jdk18on 带来的 bcprov-jdk18on 重复类；原配置也是靠 exclude 掉它才成立的。
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
}
