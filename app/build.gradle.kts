plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sari.subtitle.v2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sari.subtitle.v2"
        minSdk = 29
        targetSdk = 35
        versionCode = 25000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.24.0-final-integrated"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/keystore/sari.keystore"
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_STORE_PASSWORD") ?: "dummy_pass"
            keyAlias = System.getenv("KEYSTORE_KEY_ALIAS") ?: "sari"
            keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD") ?: "dummy_pass"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // Prefer the configured release key for Debug when it is available, so a CI APK
        // can update an installation signed with that same key. Otherwise use Android's
        // standard debug key so CI verification does not depend on release secrets.
        debug {
            val keyPath = System.getenv("KEYSTORE_PATH")
            val canUseReleaseKey = !keyPath.isNullOrBlank() && file(keyPath).isFile &&
                !System.getenv("KEYSTORE_STORE_PASSWORD").isNullOrBlank() &&
                !System.getenv("KEYSTORE_KEY_ALIAS").isNullOrBlank() &&
                !System.getenv("KEYSTORE_KEY_PASSWORD").isNullOrBlank()
            if (canUseReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        "src/main/kotlin"
    )
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
