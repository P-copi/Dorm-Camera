plugins {
    id("com.android.application")
}

val releaseKeystorePath = System.getenv("DORM_CAMERA_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("DORM_CAMERA_KEYSTORE_PASSWORD")

android {
    namespace = "com.pcopi.dormcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pcopi.dormcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.1.28"
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank() && !releaseKeystorePassword.isNullOrBlank()) {
            create("permanentRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = "dorm-camera"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("permanentRelease")?.let { signingConfig = it }
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
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
}
