plugins {
    // AGP 8.5 只测到 compileSdk 34，工程用的是 35 —— 必须 ≥ 8.6，这里跟 Gradle 8.9 对齐
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
