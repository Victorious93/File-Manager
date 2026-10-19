plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.apksig)
    api(libs.smali.dexlib2)
    implementation(libs.smali.baksmali) {
        exclude(group = "com.beust", module = "jcommander")
    }
    implementation(libs.smali.smali) {
        exclude(group = "com.beust", module = "jcommander")
    }
    implementation(libs.zip4j)
    implementation(libs.commons.net)
    implementation(libs.jsch)
    implementation(libs.smbj)
    implementation(libs.okhttp)
    api(libs.nanohttpd)
    // jadx: dex -> readable Java decompiler (Apache-2.0). See docs/JAVA_VIEW.md.
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input) {
        exclude(group = "com.beust", module = "jcommander")
    }
    // RAR extraction only (UnRAR license; see docs/ARCHIVES.md). Never used to create RAR archives.
    implementation(libs.junrar)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
