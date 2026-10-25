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
    }

    buildFeatures {
        compose = true
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

    // DeanWave sound engine (our DSP blueprint)
    implementation(project(":dsp"))
}
