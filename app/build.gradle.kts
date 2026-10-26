plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deanwaylabs.deanwave"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deanwaylabs.deanwave"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // AppAuth's redirect receiver listens on this scheme: the app's
        // own package, so Google's sign-in returns to DeanWave.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.deanwaylabs.deanwave"
    }

    buildFeatures {
        compose = true
    }

    // Release signing: the DeanWave release keystore (PKCS#12) lives in
    // CI secrets + a private Drive backup ("DeanWay Keys") — never in
    // the repo. CI decodes it to app/release.keystore before building.
    // Without it (local builds, PRs without secrets) the release build
    // stays unsigned rather than failing.
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "deanwave"
            keyPassword = System.getenv("KEY_PASSWORD")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            val keystoreFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")
            if (keystoreFile.exists() && keystoreFile.length() > 0) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Jetpack Compose (NowPlayingScreen)
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Media3 playback (PlaybackService, DeanWaveAudioProcessor)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // Torrent engine (TorrentStreamer) — FrostWire's own Maven repo,
    // version 2.0.12.9 = latest published release. Java wrappers + all four Android ABIs.
    implementation("com.frostwire:jlibtorrent:2.0.12.9")
    implementation("com.frostwire:jlibtorrent-android-arm:2.0.12.9")
    implementation("com.frostwire:jlibtorrent-android-arm64:2.0.12.9")
    implementation("com.frostwire:jlibtorrent-android-x86:2.0.12.9")
    implementation("com.frostwire:jlibtorrent-android-x86_64:2.0.12.9")

    // Google sign-in for the music Drive (AppAuth, no Google client
    // library — the uploader speaks raw Drive v3 over HTTP already).
    implementation("net.openid:appauth:0.11.1")

    // DeanWave sound engine (our DSP blueprint)
    implementation(project(":dsp"))
}
