import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing config is read from a file outside the repo (see README, "Release build").
val keyProps = Properties().apply {
    val f = File(System.getProperty("user.home"), "android/keys/covernet.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.davydenk.covernet"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.davydenk.covernet"
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "1.2"
    }

    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = File(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keyProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { buildConfig = true }
}
