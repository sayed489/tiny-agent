import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tinyagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tinyagent"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.7"
    }

    buildTypes {
        release {
            // R8 shrinks and optimizes the app (smaller APK, faster startup). Signed with the debug key for sideloading.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // android.jar stubs return defaults on the JVM; real org.json comes from the test dependency below.
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Runtime: Kotlin stdlib + Android SDK only (hard rule 2). Test-only dependencies below.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
