plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hq.retrocam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hq.retrocam"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("retro") {
            storeFile = rootProject.file("keystore/retrocam.jks")
            storePassword = "retrocam123"
            keyAlias = "retrocam"
            keyPassword = "retrocam123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("retro")
        }
        debug {
            signingConfig = signingConfigs.getByName("retro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.camera.camera2.interop.ExperimentalCamera2Interop",
            "-opt-in=androidx.camera.core.ExperimentalImageCaptureOutputFormat",
            "-opt-in=androidx.camera.core.ExperimentalGetImage",
        )
    }
    buildFeatures { viewBinding = true }
}

dependencies {
    val camerax = "1.5.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
