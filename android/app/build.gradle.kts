plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arzx.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arzx.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
    }
}

kotlin {
    jvmToolchain(17)
}
