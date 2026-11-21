plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mrmzone.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mrmzone.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // One small APK per CPU type + one universal APK that runs on every phone
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // VLC engine: plays every format (MKV, AVI, MP4, WMV, FLV, TS, WEBM...) and every audio codec
    // incl. AC3 / E-AC3 / DTS / TrueHD / FLAC / Opus, plus SRT/ASS/SSA/VobSub/PGS subtitles.
    implementation("org.videolan.android:libvlc-all:3.6.5")

    testImplementation("junit:junit:4.13.2")
}
