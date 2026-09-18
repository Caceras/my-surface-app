# Toolchain reference

These are repository pins, not a claim that each is the latest upstream version.

| Component | Pin | Source |
|---|---|---|
| Gradle | 9.7.1 | `.github/workflows/build.yml` |
| Android Gradle Plugin | 9.3.2 | `build.gradle.kts` |
| Kotlin | AGP built-in support | Root plugin configuration |
| CI JDK | Temurin 21 | Build workflow |
| Java source/target | 17 | `app/build.gradle.kts` |
| Compile/target SDK | 36 | App Gradle configuration |
| Build-tools | 36.0.0 | Explicit workflow SDK packages |
| Minimum SDK | 29 | App Gradle configuration |
| ML Kit Prompt | 1.0.0-beta4 | Nano dependency |
| Robolectric | 4.16.1 | Test dependency |

## Diagnose failures

| Error | Check |
|---|---|
| Unsupported Gradle/JDK | Use the pinned versions before changing source |
| Duplicate Kotlin plugin configuration | AGP 9 uses built-in Kotlin; do not casually reintroduce the legacy plugin |
| Missing Android SDK/build-tools | Install the explicit platform/build-tools listed above and set the SDK location |
| Unresolved ML Kit API | Check the Nano dependency and actual pinned SDK API; keep runtime ML Kit imports out of main/core |
| Resource/component checker failure | Fix the referenced source set/resource/manifest; do not disable the gate |
| Update incompatible | Export before reinstalling; see [signing](delivery.md#signing) |
| Release permission failure | Check the workflow's contents permission and repository Actions settings |

## Upgrade deliberately

Change one related toolchain set at a time. Run `python tools/check.py`, JVM tests, both flavor lint tasks and both APK builds. Inspect fresh screenshots when changing Android/graphics versions. Run hardware inference/speech checks after SDK upgrades. The workflow installs SDK packages explicitly: changing compileSdk alone does not update those package declarations.

## Workflow action pins

Actions are pinned to full commit SHAs in the workflow. Reviewed releases for this pass: checkout 7.0.1, setup-java 6.0.1, upload-artifact 7.0.1, download-artifact 8.0.1 and Gradle actions 6.3.0. Hosted Ubuntu supplies Python; Android SDK installation is checked once per quality job. See [the workflow audit](iteration-workflow.md) for official sources and the update policy.
