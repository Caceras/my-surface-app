plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.surfacelab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.surfacelab"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Deprecated in Kotlin 2.0, removed in 2.2. If KOTLIN_VERSION is
    // raised to 2.2+, replace this with a top-level:
    //   kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Deliberately no dependencies: everything here uses framework APIs only,
// which keeps the build fast and gives dependency resolution nothing to
// break on.
dependencies { }
