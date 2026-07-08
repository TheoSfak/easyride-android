plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.easyride.ridemode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.easyride.ridemode"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "BOOTSTRAP_BASE_URL", "\"https://theeasyride.eu\"")
        buildConfigField("String", "SERVER_CONFIG_URL", "\"https://raw.githubusercontent.com/TheoSfak/easyride-android/main/server-config.json\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
