plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.saif.notchify"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.saif.notchify"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK sideloads straight away.
            // Use Build > Generate Signed App Bundle / APK in Android Studio for a release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Nothing Glyph Matrix SDK 2.0 (github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit).
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))
}
