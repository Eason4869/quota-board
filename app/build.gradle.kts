import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ── 发布签名 ──────────────────────────────────────────────
// 优先读环境变量（CI 用 Secrets 注入），其次读工程根目录的 keystore.properties（本地构建），
// 两者都没有时回退 debug 签名，保证任何环境都能编译出 APK。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { stream -> keystoreProperties.load(stream) }
}

fun signingValue(propKey: String, envKey: String): String? {
    val fromEnv = System.getenv(envKey)
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val fromFile = keystoreProperties.getProperty(propKey)
    return if (fromFile.isNullOrBlank()) null else fromFile
}

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_PATH")
    ?.let { path -> rootProject.file(path) }
    ?.takeIf { file -> file.exists() }

val hasReleaseSigning = releaseStoreFile != null &&
    signingValue("storePassword", "KEYSTORE_PASSWORD") != null &&
    signingValue("keyAlias", "KEY_ALIAS") != null &&
    signingValue("keyPassword", "KEY_PASSWORD") != null

android {
    namespace = "com.yusheng.quota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yusheng.quota"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.4"
        resourceConfigurations += listOf("en", "zh-rCN")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // 关于页用 BuildConfig.VERSION_NAME；AGP 8 起默认关闭，不开会编译失败
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.1")

    // 桌面小组件
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
