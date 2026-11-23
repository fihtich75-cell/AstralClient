plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.astral.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.astral.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.2.0"
    }

    signingConfigs {
        // Fixed key (committed): every build is signed the same, so a new APK installs over the old one.
        create("astral") {
            storeFile = file("astral.keystore")
            storePassword = "astral-client"
            keyAlias = "astral"
            keyPassword = "astral-client"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("astral")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
