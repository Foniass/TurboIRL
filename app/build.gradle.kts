plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.turboirl.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.turboirl.app"
        minSdk = 26
        // 34 rather than 35: no forced edge-to-edge, same foreground service rules
        targetSdk = 34
        versionCode = 18
        versionName = "0.96"
        // libsrt + OpenSSL are native: only ship the ABI of the target phone (Redmi Note 14 Pro 5G)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded tool: the debug key is enough
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.github.thibaultbee.srtdroid:srtdroid-core:1.10.0")
    testImplementation("junit:junit:4.13.2")
}
