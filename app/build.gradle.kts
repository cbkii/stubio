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
        versionName = "1.3." + (System.currentTimeMillis() / 100000).toInt()  // User-facing version
        versionCode = (System.currentTimeMillis() / 1000).toInt()    // Internal version, auto increment as timestamp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APP_DESCRIPTION",
            "\"Routes video intents to SmartTube and VLC, fallbacks to Kodi, MX, YouTube. A Stremio external player stub.\""
        )

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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