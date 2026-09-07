plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.umbra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.umbra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Реальные устройства: arm64-v8a (современные) и armeabi-v7a (старые).
        // x86_64 — для эмулятора на x86-хостах. x86 (32-бит) не нужен.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Для разработки подписываем release тем же debug-ключом (устанавливается).
            // Для публикации в Play Store замените на собственный keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Требуется libsignal-android (использует Java 8+ API на старых версиях Android).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // libsignal-client использует нативный код (JNI) — оставляем abiFilters по умолчанию.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Нативные либы desktop-платформ из libsignal-client не нужны на Android —
            // они лишь раздувают APK. Нативные .so для Android даёт libsignal-android.
            excludes += setOf(
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
                "libsignal_jni.so",
                "libsignal_jni_amd64.so",
                "libsignal_jni_arm64.so",
                "libsignal_jni_testing.so",
            )
        }
        jniLibs {
            // Тестовая нативная либа libsignal (~13 МБ на ABI) не нужна в боевом APK.
            excludes += setOf("**/libsignal_jni_testing.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.security.crypto)
    implementation(libs.libsignal.client)
    // libsignal-android — нативные .so (arm64-v8a, armeabi-v7a, x86, x86_64).
    // Без него libsignal-client не найдёт libsignal_jni.so на устройстве.
    implementation(libs.libsignal.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.ui.tooling)
}
