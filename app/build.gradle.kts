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

        // The build number comes from CI (-PbuildNumber=<run number>) and
        // falls back to "dev" for a local build. The launcher screen shows
        // it, which is the whole point: after a change is pushed you can
        // tell at a glance whether the APK on the phone is the new one.
        versionName = "2.0." + (project.findProperty("buildNumber") ?: "dev")
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

    testOptions {
        unitTests {
            // Robolectric renders the real layouts, so it needs the real
            // resources. Without this every findViewById comes back null and
            // the failures look like bugs in the app.
            isIncludeAndroidResources = true
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
    // One artifact, because the Prompt API is a general generative client:
    // free-form prompts, system instructions, temperature, streaming. The
    // task-shaped siblings (genai-summarization and friends) are a narrower
    // API for the same model and are not needed once you can prompt it.
    //
    // It talks to Android AICore, the system service that hosts Gemini Nano.
    // No model ships inside the APK; nothing leaves the device.
    "nanoImplementation"("com.google.mlkit:genai-prompt:1.0.0-beta4")

    // Test-only, so nothing here reaches an APK and the zero-dependency
    // invariant is untouched. Robolectric runs the activities on the JVM,
    // which is the only way this project sees its own UI without a phone.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
}
