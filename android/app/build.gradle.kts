import java.io.File
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val configuredServerUrl = providers.gradleProperty("umbra.serverUrl")
    .orElse(providers.environmentVariable("UMBRA_SERVER_URL")).getOrElse("").trim()
fun urlLiteral(value: String): String {
    val uri = URI(value)
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/")) { "Umbra URL must be an HTTP(S) origin" }
    return "\"" + value.trimEnd('/') + "/\""
}

// ---------------------------------------------------------------------------
// Стабильная подпись APK.
//
// Чтобы новое приложение просто ОБНОВЛЯЛО прежнее (без удаления), каждая
// сборка должна быть подписана ОДНИМ И ТЕМ ЖЕ ключом. Параметры берутся,
// в порядке приоритета, из:
//   1) android/keystore.properties — файл НЕ коммитится (образец см. рядом,
//      keystore.properties.example); ключ приложения храните в надёжном месте;
//   2) переменных окружения UMBRA_SIGNING_KEYSTORE / UMBRA_SIGNING_KEYSTORE_PASSWORD /
//      UMBRA_SIGNING_KEY_ALIAS / UMBRA_SIGNING_KEY_PASSWORD (GitHub Actions — секреты);
//   3) если ни того, ни другого нет — стандартная debug-подпись AGP (только
//      для локальной отладки; такие APK не обновляют установленные).
//
// ПОДПИСЬ МЕНЯТЬ НЕЛЬЗЯ: разные ключи = Android считает пакеты разными и
// требует удаления старого приложения (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
// ---------------------------------------------------------------------------
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
val keystoreFileRaw = envOrNull("UMBRA_SIGNING_KEYSTORE") ?: signingProps.getProperty("storeFile")
val keystorePassword = envOrNull("UMBRA_SIGNING_KEYSTORE_PASSWORD") ?: signingProps.getProperty("storePassword")
val signingKeyAlias = envOrNull("UMBRA_SIGNING_KEY_ALIAS") ?: signingProps.getProperty("keyAlias")
val signingKeyPassword = envOrNull("UMBRA_SIGNING_KEY_PASSWORD") ?: signingProps.getProperty("keyPassword")
val keystoreFile: File? = keystoreFileRaw?.let { rootProject.file(it) }
val useStableSigning = keystoreFile != null && keystoreFile.isFile &&
    !keystorePassword.isNullOrEmpty() && !signingKeyAlias.isNullOrEmpty() && !signingKeyPassword.isNullOrEmpty()
if (useStableSigning) {
    println("Umbra: APK будет подписан стабильным ключом (${keystoreFile!!.name}).")
} else {
    println("Umbra: стабильный ключ не задан — используется debug-подпись AGP. " +
        "Такой APK не обновит установленную сборку (нужно удалять).")
}

android {
    namespace = "com.umbra.app"
    compileSdk = 35

    signingConfigs {
        if (useStableSigning) {
            create("stable") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.umbra.app"
        minSdk = 26
        targetSdk = 35
        // Перед каждым выпуском увеличивайте versionCode, иначе Android не даст
        // обновить установленное приложение («Приложение не установлено»).
        versionCode = 9
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (useStableSigning) signingConfig = signingConfigs.getByName("stable")
            buildConfigField("String", "SERVER_URL", urlLiteral(configuredServerUrl.ifEmpty { "http://10.0.2.2:8080" }))
        }
        release {
            if (useStableSigning) signingConfig = signingConfigs.getByName("stable")
            buildConfigField("String", "SERVER_URL", urlLiteral(configuredServerUrl.ifEmpty { "https://example.invalid" }))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Подпись берётся из стабильного ключа (см. выше); сам файл ключа в git не хранится.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Дешугаринг java.time/streams для библиотек (EncryptedSharedPreferences и т.п.).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.ui.tooling)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        require(configuredServerUrl.startsWith("https://")) {
            "Release requires -Pumbra.serverUrl=https://your-server or UMBRA_SERVER_URL"
        }
    }
}
