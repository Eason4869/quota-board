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

// 回退 debug 签名是为了「任何环境都能编出 APK」，但 release 包一旦用 debug 密钥发出去，
// 装了正式签名版的用户就只能卸载重装（签名不匹配），所以至少要让这次构建叫一声。
// CI 的 tag 构建里这是硬失败（见 .github/workflows/build.yml），不走这条警告。
if (!hasReleaseSigning && gradle.startParameter.taskNames.any { it.contains("release", true) }) {
    logger.warn(
        "⚠️ 未配置发布签名（KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD 或 keystore.properties）。" +
            "本次 assembleRelease 会用 debug 密钥签名，产物无法覆盖安装正式版。"
    )
}

android {
    namespace = "com.yusheng.quota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yusheng.quota"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "1.2.30"
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
    testOptions.unitTests.isIncludeAndroidResources = true
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    // WebView 能力扩展：关闭「算法暗色」（会把不支持的站点整页刷成黑色）
    implementation("androidx.webkit:webkit:1.12.1")

    // Dock 的背景模糊。Android 没有系统级的 backdrop blur 接口，Haze 的做法是
    // 把背后内容先渲进一个 GraphicsLayer 再对那份拷贝做 RenderEffect 模糊。
    // 版本**故意钉在 1.1.1**：它是最后一个用 Kotlin 2.0.21 + Compose UI 1.7.x 编出来的，
    // 与本工程的 Kotlin 2.0.21 完全对齐；1.2.0 起改用 Kotlin 2.1 编，
    // 而 Kotlin 2.0 的编译器读不了 2.1 的元数据（会直接编译失败）。
    // 真要升 Haze，就必须先把 Kotlin 与 Compose BOM 一起升上去。
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.1")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}


