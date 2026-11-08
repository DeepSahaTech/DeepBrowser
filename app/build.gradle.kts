plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.deepbrowser.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deepbrowser.tv"
        minSdk = 28          // Fire OS 7 (Android 9) is the lowest supported target
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // replace with real keystore before publishing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = true; warningsAsErrors = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.webkit:webkit:1.13.0")
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg audio fallback (AC-3/E-AC-3/DTS...). Jellyfin build, GPL-3.0. Its POM declares media3 1.7.1 but its 33 media3
    // references were verified against 1.6.1 (javap), so exclude androidx.media3 to keep every media3 module on $media3.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+1") { exclude(group = "androidx.media3") }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // android.jar only stubs org.json in unit tests
}
