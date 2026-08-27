plugins {
    id("com.android.application")
}

android {
    namespace = "com.caceras.surfacelab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.caceras.surfacelab"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    // Two builds of the same app. "core" is the original zero-dependency
    // template. "nano" adds on-device Gemini Nano and is the only place any
    // dependency is allowed to exist. The suffixed application id lets both
    // sit on the phone at once for a side-by-side comparison.
    flavorDimensions += "brain"
    productFlavors {
        create("core") {
            dimension = "brain"
            versionNameSuffix = "-core"
        }
        create("nano") {
            dimension = "brain"
            applicationIdSuffix = ".nano"
            versionNameSuffix = "-nano"
        }
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
}

dependencies {
    // "core" declares nothing, on purpose -- that invariant is enforced by
    // tools/verify.py and is the reason this template builds first time.
    //
    // The GenAI APIs talk to Android AICore, the system service that hosts
    // Gemini Nano. No model ships inside the APK; nothing leaves the device.
    "nanoImplementation"("com.google.mlkit:genai-summarization:1.0.0-beta1")
    "nanoImplementation"("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    "nanoImplementation"("com.google.mlkit:genai-rewriting:1.0.0-beta1")
}
