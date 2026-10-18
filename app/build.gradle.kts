import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.amission"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.amission"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"
    }

    // Release signing details live in keystore.properties (git-ignored, see .gitignore). Without that file the
    // release build is simply left unsigned, so a fresh clone still builds.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
    if (keystoreProps != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Phones are arm; the x86 copies of the map engine only serve emulators and add about 25 MB.
            // Pass -PallAbis to build a release that also runs on an x86 emulator.
            if (!project.hasProperty("allAbis")) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":msp"))
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")

    implementation("org.maplibre.gl:android-sdk:11.13.5")
    // MapLibre ships OkHttp at runtime only; it is needed here to set the User-Agent and limit parallel tile requests.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
}
