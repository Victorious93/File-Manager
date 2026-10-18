plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.victorious.filemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.victorious.filemanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Optional signing-cert pins for bridge callers, e.g.
        // -PfleetCallerCertPins="ai.droidcommand.app=<sha256hex>;ai.droidcommand.app.debug=<sha256hex>"
        // Empty (default) = package-name allowlist only. See docs/FLEET_BRIDGE.md.
        val pins = (project.findProperty("fleetCallerCertPins") as String?).orEmpty().replace("\"", "")
        buildConfigField("String", "FLEET_CALLER_CERT_PINS", "\"$pins\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    // File-Manager-only debug key when DEBUG_KEYSTORE_* env/properties are set.
    // Falls back to the Android default debug keystore otherwise (local without secrets).
    // Never use /workspace/fleet-signing — that is a shared fleet key.
    signingConfigs {
        getByName("debug") {
            val storePath = (
                System.getenv("DEBUG_KEYSTORE_FILE")
                    ?: (project.findProperty("DEBUG_KEYSTORE_FILE") as String?)
            )?.trim().orEmpty()
            val storePass = (
                System.getenv("DEBUG_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("DEBUG_KEYSTORE_PASSWORD") as String?)
            )?.trim().orEmpty()
            val alias = (
                System.getenv("DEBUG_KEY_ALIAS")
                    ?: (project.findProperty("DEBUG_KEY_ALIAS") as String?)
            )?.trim().orEmpty()
            val keyPass = (
                System.getenv("DEBUG_KEY_PASSWORD")
                    ?: (project.findProperty("DEBUG_KEY_PASSWORD") as String?)
            )?.trim().orEmpty()
            val store = if (storePath.isNotEmpty()) file(storePath) else null
            if (store != null && store.isFile && storePass.isNotEmpty() && alias.isNotEmpty() && keyPass.isNotEmpty()) {
                storeFile = store
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
                println("signing: using File Manager debug keystore at ${store.absolutePath}")
            } else {
                println("signing: DEBUG_KEYSTORE_* not fully set; using Android default debug key")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,NOTICE,NOTICE.txt,DEPENDENCIES}"
            excludes += "/META-INF/{LICENSE.md,LICENSE-notice.md,NOTICE.md}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.apksig)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsu.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.security.crypto)
    implementation(libs.zxing.core)
    implementation(libs.androidx.biometric)
    // Apache-2.0 PTY + VT100 view (jackpal/Termux modules). Falls back to the pipe shell if libtermux fails to load.
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.okhttp)
    // Phase 12 — media viewers + widgets (all Apache-2.0)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)

    // Instrumented smoke tests (run on an emulator by the "Emulator smoke tests" CI job).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
