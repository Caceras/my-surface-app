# Toolchain versions and build failures

The versions pinned in `scripts/scaffold.py` were current when the skill was
written and will age. Android's build tooling is tightly coupled — Gradle, the
Android Gradle Plugin (AGP), the Kotlin plugin, the JDK, and `compileSdk` each
constrain the others — so the majority of first-build failures are version skew
rather than anything wrong with the generated code.

## Current pins

| Component | Value | Constrained by |
|---|---|---|
| Gradle | 8.9 | AGP requires a minimum Gradle version |
| AGP | 8.6.0 | Determines the maximum supported `compileSdk` |
| Kotlin plugin | 2.0.20 | Must be compatible with AGP |
| JDK | 17 | AGP 8.x requires 17+ |
| `compileSdk` / `targetSdk` | 35 | Must be ≤ what AGP supports |
| `minSdk` | 26 | Chosen for API availability, not a constraint |

Change these in the constants block near the top of `scripts/scaffold.py`.

## Mapping errors to fixes

**"Android Gradle plugin requires Java 17 to run. You are currently using Java N"**
Raise `java-version` in the workflow's `setup-java` step.

**"Minimum supported Gradle version is X. Current version is Y"**
Raise `GRADLE_VERSION` to at least X.

**"compileSdk N is not supported by this version of the Android Gradle Plugin"**
Either raise `AGP_VERSION`, or lower `COMPILE_SDK` to what the current AGP
supports. As a stopgap, `android.suppressUnsupportedCompileSdk=<N>` in
`gradle.properties` silences the check — useful to get unblocked, but it is
suppressing a real incompatibility, so prefer bumping AGP.

**"This version of the Kotlin Gradle plugin is not compatible with AGP"**
Move `KOTLIN_VERSION` to a release that matches the AGP major version.

**"Could not resolve com.android.tools.build:gradle:X"**
Usually a version that does not exist — check the exact AGP version string, and
confirm `google()` is present in `settings.gradle.kts` `pluginManagement`.

**"SDK location not found" (local builds only)**
Android Studio writes `local.properties`; it is gitignored deliberately. The CI
build does not need it because `setup-android` sets `ANDROID_HOME`.

**"INSTALL_FAILED_UPDATE_INCOMPATIBLE" (on the phone, not in CI)**
Not a build failure. The installed app was signed with a different key.
Uninstall the existing app first. This commonly happens when switching between
a locally built APK and a CI-built one, because the debug keystores differ.

**"App not installed" with no further detail**
Usually the downloaded file was a workflow artifact `.zip` rather than the
release `.apk`. Check what was actually downloaded before debugging further.

## Verifying a version combination

Rather than guessing at compatible versions, check the AGP release notes, which
state the required Gradle version and supported `compileSdk` for each release.
If the user has web access available, look it up rather than iterating through
failed CI runs — each round trip is several minutes.

## Making CI faster

The first run is slow because it downloads Gradle, the SDK, and the platform
JAR. `gradle/actions/setup-gradle` caches Gradle distributions and build caches
across runs automatically, so subsequent pushes are substantially quicker. If
iteration speed becomes the bottleneck, building locally in Android Studio and
installing over USB beats waiting on CI.
