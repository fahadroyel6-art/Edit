plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ultravidmerger.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ultravidmerger.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0.0"
        // The maintained FFmpeg build ships arm64-v8a + x86_64 only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    val ksPath = System.getenv("KEYSTORE_FILE")
    val canSign = !ksPath.isNullOrBlank() && file(ksPath).exists()
    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            pickFirsts += listOf("libc++_shared.so", "libavcodec.so", "libavformat.so",
                "libavutil.so", "libavfilter.so", "libswscale.so", "libswresample.so",
                "libavdevice.so", "libffmpegkit.so", "libffmpegkit_abidetect.so")
            useLegacyPackaging = false
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // FFmpeg 8.1 LTS — community maintained FFmpegKit fork (Maven Central)
    implementation("dev.ffmpegkit-maintained:ffmpeg:8.1.9")

    // Media3 Transformer (re-encode fallback only)
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
