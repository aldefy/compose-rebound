plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    id("io.aldefy.rebound")
}

android {
    namespace = "io.aldefy.rebound.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.aldefy.rebound.sample"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":rebound-runtime"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
}

rebound {
    enabled.set(true)
}
