plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.family.guard"
    compileSdk = 34
    defaultConfig {
        applicationId = "ir.family.guard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Injected from GitHub Actions secrets so you don't type them on the phone
        buildConfigField("String", "SB_URL", "\"${System.getenv("SB_URL") ?: ""}\"")
        buildConfigField("String", "SB_KEY", "\"${System.getenv("SB_ANON_KEY") ?: ""}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
