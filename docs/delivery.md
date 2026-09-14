# Delivery and updates

[Download Nano preview](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant/aegentica-ai-nano.apk) · [Install guide](getting-started.md)

The rebrand preserves `com.caceras.surface.nano` and existing backups. Ægentica AI replaces the Surface Preview display name; it does not create a new app data identity. Signing compatibility still governs in-place updates.

## Which release

`improve-pixel-assistant` publishes a prerelease at `preview-improve-pixel-assistant`. Its Nano asset is the current Ægentica AI experience. The main branch publishes the rolling `debug-latest` release. GitHub's `/releases/latest` points to the non-prerelease experience and may therefore be older.

The release title includes the CI build number. The app uses version `3.0.<build>-nano` and displays it in Settings. A stable URL is a convenience, not proof that Android accepted an update: verify the installed version.

| Build | Application ID | Use |
|---|---|---|
| Ægentica AI Nano | `com.caceras.surface.nano` | Actual assistant on supported devices |
| Ægentica AI core | `com.caceras.surface` | Deterministic developer demo |
| Original Pixel Surface Lab Nano | `com.caceras.surfacelab.nano` | Earlier application identity; no automatic data migration |

## Signing

Android updates require a compatible signing identity as well as the same application ID and an acceptable version code. CI debug keys are not persisted by default. Different runs/local machines can therefore produce APKs that cannot update each other in place.

**Before reinstalling:** Export conversation from Settings, keep the file, then uninstall only the app you intend to replace. Install the new APK and Restore conversation. Exports include retained chats and drafts, not speech preferences. Reconfigure voice if needed.

For consistent personal-preview updates, the workflow already supports these **private repository secrets**:

| Secret | Value |
|---|---|
| `SURFACE_KEYSTORE_BASE64` | Base64-encoded private keystore |
| `SURFACE_KEYSTORE_PASSWORD` | Keystore password |
| `SURFACE_KEY_ALIAS` | Signing alias |
| `SURFACE_KEY_PASSWORD` | Key password |

The workflow restores the key into the runner's temporary directory and passes credentials through environment variables. It does not include the keystore in APK artifacts. Keep a private, recoverable backup of the key; do not commit it, publish it as an artifact, or put it in a public cache. These secrets have not been provisioned by the app overhaul. Moving from a generated debug key to a personal key can require one export/reinstall/restore cycle.

For a local build, `app/build.gradle.kts` accepts the equivalent `SURFACE_KEYSTORE_FILE`, password and alias environment variables. Do not put secret values in a shell history or shared logs.

The branded assets are `aegentica-ai-nano.apk` and `aegentica-ai-core.apk`. Legacy `pixel-surface-lab` asset names are byte-identical aliases so existing bookmarks keep working.

## CI

`.github/workflows/build.yml` runs the static pre-flight, then JVM tests and both flavor builds. Preview publication requires the test and build jobs to pass. Pull-request runs validate but do not publish branch releases. Pushes publish a rolling prerelease for their branch; main publishes the rolling main release.

Preview publication replaces a rolling tag/release. There can be a brief unavailable interval while assets are replaced. For reproducibility, record the release commit and CI run instead of relying only on the bookmark. A rerun may reuse the same version code; it is not a new installed-version guarantee.

Use `.apk` release assets on Android. GitHub workflow artifact downloads are ZIP archives intended for development and inspection.

## Distribution boundary

These are debug/personal preview APKs. Publishing to Google Play requires a deliberate release build/signing strategy, device validation, current policy review and accurate product/privacy disclosures. The repository does not claim a production rollout, signing-key escrow service, or automatic cross-device migration.
