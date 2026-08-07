import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/** 读取根目录 keystore.properties（正式签名凭证，不入库；缺失时用占位符阻止误发布） */
private val signingProps: Properties by lazy {
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.meshchat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meshchat.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 124
        versionName = "1.1.62"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // JVM 单测中 android.util.Log 等 Android API 返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    signingConfigs {
        create("release") {
            // 正式签名：keystore.properties 提供凭证（不入库）。
            // 缺失时占位符保证 assembleRelease 直接失败，防止误发未签名/假签名包
            storeFile = rootProject.file(signingProps.getProperty("storeFile", "meshchat-release.keystore"))
            storePassword = signingProps.getProperty("storePassword", "NO_SIGNING_CONFIG")
            keyAlias = signingProps.getProperty("keyAlias", "meshchat")
            keyPassword = signingProps.getProperty("keyPassword", "NO_SIGNING_CONFIG")
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源压缩（上架正式包）：serialization/Room keep 规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.biometric:biometric:1.1.0")   // v1.1.58 应用锁指纹解锁
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
