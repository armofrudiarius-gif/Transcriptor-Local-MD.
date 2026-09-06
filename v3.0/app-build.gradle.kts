plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "md.localtranscript"
    compileSdk = 35

    defaultConfig {
        applicationId = "md.localtranscript"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.0-max-precision"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    testImplementation("junit:junit:4.13.2")
}
