import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arzx.music"
    compileSdk = 35

    val signingProperties = rootProject.file("keystore.properties")
    if (signingProperties.exists()) {
        val props = Properties().apply {
            load(signingProperties.inputStream())
        }
        signingConfigs {
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.arzx.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.6.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // RecyclerView (0.6.3, audit S1): the queue overlay is the
    // one list that owns its viewport, so it gets true view
    // recycling. (Shell screens live inside one big ScrollView
    // where an expanded RecyclerView would bind every row
    // anyway — see the 0.6.3 notes in MainActivity.addRows.)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Media3 ExoPlayer: googlevideo stream URLs are bound to the
    // client that requested them, so playback must send the SAME
    // User-Agent as the Innertube context that produced the URL.
    // Bare MediaPlayer sends the Stagefright UA and gets rejected
    // (error -38 on device, 2026-11-07). ExoPlayer's HTTP data
    // source takes a per-player UA + headers.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    // On-device YouTube Music search + stream extraction (no backend).
    // GPL-3.0, fine for this personal build.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
}
