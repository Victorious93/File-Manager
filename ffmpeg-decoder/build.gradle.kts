plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.txt")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    if (file("src/main/jni/ffmpeg/android-libs").exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    api(libs.media3.common)
    api("androidx.media3:media3-decoder:1.4.1")
    implementation(libs.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.8.2")
}
