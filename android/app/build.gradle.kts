plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.levi.dock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.levi.dock"
        minSdk = 23
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
