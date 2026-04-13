plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.intentrouter.stubio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.intentrouter.stubio"
        minSdk = 28
        targetSdk = 35

        val ciVersionName = (project.findProperty("VERSION_NAME") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
        versionName = ciVersionName ?: "1.4.0"

        val ciVersionCode = (project.findProperty("VERSION_CODE") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() }
        versionCode = ciVersionCode?.toIntOrNull() ?: 140

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "APP_DESCRIPTION",
            "\"Routes Stremio playback intents to TV-optimised external players.\""
        )
    }

    val signingStoreFile = (project.findProperty("ANDROID_SIGNING_STORE_FILE") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
    val signingStorePassword = (project.findProperty("ANDROID_SIGNING_STORE_PASSWORD") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val signingKeyAlias = (project.findProperty("ANDROID_SIGNING_KEY_ALIAS") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val signingKeyPassword = (project.findProperty("ANDROID_SIGNING_KEY_PASSWORD") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

    val hasAllSigningSecrets = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword)
        .all { !it.isNullOrBlank() }

    if (hasAllSigningSecrets) {
        val keystoreFile = file(signingStoreFile!!)
        require(keystoreFile.exists()) {
            "Signing keystore not found at '${keystoreFile.absolutePath}'."
        }
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasAllSigningSecrets) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.preference.ktx)
}
