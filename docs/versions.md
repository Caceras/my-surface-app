# Toolchain versions

Gradle, AGP, the JDK and `compileSdk` all constrain each other. Nearly every
"this repo is broken" report is version skew, not code.

## What this repo builds with

| Piece | Version | Set in |
|---|---|---|
| Gradle | 9.7.1 | `.github/workflows/build.yml`, `tools/scaffold.py` |
| Android Gradle Plugin | 9.3.2 | `build.gradle.kts` |
| Kotlin | built into AGP 9 | — |
| JDK (build) | 21 | `.github/workflows/build.yml` |
| Java source/target | 17 | `app/build.gradle.kts` |
| compileSdk / targetSdk | 36 | `app/build.gradle.kts` |
| minSdk | 29 | `app/build.gradle.kts` |
| ML Kit GenAI | 1.0.0-beta1 | `app/build.gradle.kts` (`nano` only) |

`compileSdk 37` is available. 36 is deliberate: it is the current-minus-one that
every CI image and local SDK already has, and bumping it is a one-line change
when you want it.

## Error → fix

**`Minimum supported Gradle version is 9.5.0. Current version is 9.4.1.`**
AGP 9.3 requires Gradle 9.5+. Raise the `gradle-version` in the workflow, and
locally `brew upgrade gradle` (or your equivalent). There is no wrapper in this
repo by design, so your own Gradle is the one that matters.

**`The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
support since AGP 9.0.`**
Not a warning — the build fails. Delete the plugin from both `build.gradle.kts`
files. AGP 9 has Kotlin built in and takes its JVM target from `compileOptions`,
so the old `kotlinOptions { jvmTarget = "17" }` block goes too.

**`Unsupported class file major version` / `Unsupported Java`**
The JDK is newer or older than the AGP expects. Use Temurin 21 for AGP 9.

**`Installed Build Tools revision X is corrupted`**
Delete `~/Library/Android/sdk/build-tools/X` and let the build re-download it.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**
The APK on the phone was signed with a different key — usually a local build
meeting a CI build. Uninstall first. See `delivery.md`.

**`Failed to find Build Tools revision` in CI**
`android-actions/setup-android@v3` installs what `compileSdk` asks for. If you
raise `compileSdk`, nothing else needs to change.

**Release step fails with 403**
The workflow is missing `permissions: contents: write`. `verify.py` checks for
this because the error message does not say it.

**`compileNanoDebugKotlin` fails with unresolved ML Kit symbols**
The dependency lines are `"nanoImplementation"(...)`, in quotes, because the
flavour configurations do not exist until the flavours are declared. Moving them
to plain `implementation(...)` would pull ML Kit into `core` and break the
zero-dependency invariant.

## Bumping safely

1. Change one thing.
2. `python tools/verify.py . && python tools/test_verify.py`
3. `gradle assembleCoreDebug assembleNanoDebug`
4. Commit. If CI disagrees with your machine, the JDK is the usual difference.
