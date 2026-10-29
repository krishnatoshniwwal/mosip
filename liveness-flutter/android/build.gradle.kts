group = "io.mosip.registration.mosip_face_liveness"
version = "0.1.0"

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "io.mosip.registration.mosip_face_liveness"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // The liveness engine and signed model packages use java.nio.file (Android 8.0, API 26).
        minSdk = 26
        consumerProguardFiles("../../liveness-android/consumer-rules.pro")
    }
}

// The liveness sources (core, engine, Android adapters), their dependencies and the bundled models: the same
// script the standalone liveness-android AAR build applies.
apply(from = "../../liveness-android/liveness-library.gradle")
