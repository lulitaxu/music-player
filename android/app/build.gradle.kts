plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arzx.music"
    compileSdk = 35

    // Signing identity (0.6.6): ONE permanent keystore signs every build,
    // so in-app updates install in place. Before this, CI signed each run
    // with a fresh throwaway debug key and Android refused every update
    // ("package conflicts with an existing package").
    // Canonical source = environment variables. CI injects them from
    // GitHub secrets (keystore decoded to a temp path); locally they are
    // derived from keys/keystore.env at the repo root, which is
    // gitignored — never commit a keystore or its password.
    //   ANDROID_KEYSTORE_FILE     path to the .keystore file
    //   ANDROID_KEYSTORE_PASSWORD store password
    //   ANDROID_KEY_ALIAS         key alias
    //   ANDROID_KEY_PASSWORD      key password
    // When the vars are absent (plain local build), debug falls back to
    // the default Android debug key and release is left unsigned.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("stable") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.arzx.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.6.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Null when the env vars are absent -> default debug key.
            signingConfig = signingConfigs.findByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("stable")
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
    // Physics-based springs for Views (0.6.9 motion pack): the
    // one motion system behind the player morph, artwork
    // breathing and press feedback (see Motion.kt). Library
    // only — it adds no permissions and no components.
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
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
