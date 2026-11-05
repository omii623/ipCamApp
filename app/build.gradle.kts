plugins {
    alias(libs.plugins.android.application)
}

// Egyedi build-azonosító (rövid hash), ami minden fordításnál frissül
val buildHash: String = "%08x".format(System.currentTimeMillis().hashCode())

android {
    namespace = "com.example.ipcamapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ipcamapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_HASH", "\"$buildHash\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.recyclerview)
    // QR-kód generálás és beolvasás (kamera megosztás két telefon között)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(libs.junit)
    // valódi org.json a unit tesztekhez (az android.jar stubja helyett)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}