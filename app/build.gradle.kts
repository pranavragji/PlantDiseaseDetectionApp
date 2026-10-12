plugins {
    id("com.android.application")
}

android {
    namespace = "com.pranavragji.leafcheck"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pranavragji.leafcheck"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"
        // real phones plus the x86_64 emulator; 32-bit x86 only adds APK size
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        // release build for the GitHub APK; signed with the debug key so anyone can rebuild and sideload it
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // the model is memory-mapped straight out of the APK, so it must stay uncompressed
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("androidx.core:core:1.16.0")
    testImplementation("junit:junit:4.13.2")
}
