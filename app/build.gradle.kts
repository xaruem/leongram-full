plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.hunta.bot"
    compileSdk = 34
    defaultConfig {
        applicationId = "uz.hunta.bot"
        minSdk = 26
        targetSdk = 33
        versionCode = 15
        versionName = "9.1-leongram"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
