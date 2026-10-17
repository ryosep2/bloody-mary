plugins {
    id("com.android.application")  // AGP 9 compiles Kotlin itself
}

android {
    namespace = "com.bloodymary.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bloodymary.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
