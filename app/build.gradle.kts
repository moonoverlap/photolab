plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moonoverlap.photocalendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moonoverlap.photocalendar"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.4"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "photocal2026"
            keyAlias = "photocal"
            keyPassword = "photocal2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
