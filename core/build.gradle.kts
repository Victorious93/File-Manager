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
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
