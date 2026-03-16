plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.intentrouter.stubio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.intentrouter.stubio"
        minSdk = 23  // Ensure old Android TVs can run the app
        //noinspection OldTargetApi
        targetSdk = 34  // Use stable latest for TVs, check latest API for no issues else keep previous stable

        // VERSION_NAME: CI sets this via Gradle property or env var; fallback to timestamp for local builds
        val ciVersionName = (project.findProperty("VERSION_NAME") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
        versionName = ciVersionName ?: ("1.3." + (System.currentTimeMillis() / 100000).toInt())

        // VERSION_CODE: CI sets this via Gradle property or env var; fallback to timestamp for local builds
        val ciVersionCode = (project.findProperty("VERSION_CODE") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() }
        versionCode = ciVersionCode?.toInt() ?: (System.currentTimeMillis() / 1000).toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APP_DESCRIPTION",
            "\"Routes video intents to SmartTube and VLC, fallbacks to Kodi, MX, YouTube. A Stremio external player stub.\""
        )
    }

    // Release signing config – only applied when all required values are present.
    // Values come from Gradle properties (e.g. -PANDROID_SIGNING_STORE_FILE=…) or environment variables.
    // Local debug builds are unaffected when secrets are absent.
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
            "Signing keystore not found at '${keystoreFile.absolutePath}'. " +
            "Ensure ANDROID_SIGNING_STORE_FILE points to an existing file."
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasAllSigningSecrets) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        // Explicitly enabling BuildConfig generation
        buildConfig = true
        // Compose is not used, so it's set to false
        compose = false
    }
}

dependencies {
    //noinspection UseTomlInstead,GradleDependency
    implementation("androidx.appcompat:appcompat:1.3.0")
    implementation("androidx.core:core-ktx:1.3.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.5.31")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(libs.androidx.preference.ktx)
    implementation(libs.protolite.well.known.types)
}