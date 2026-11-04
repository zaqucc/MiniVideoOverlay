plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.minivideooverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.minivideooverlay"
        // minSdk 29: MediaStore RELATIVE_PATH and ImageDecoder EXIF handling.
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "2.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies: only the Android framework and Kotlin stdlib are used.
