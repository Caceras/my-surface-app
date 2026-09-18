# Delivery and updates

> **Everyday workspace update:** [Current behavior and limits](everyday-workspace.md) covers Today · AI · Library, SQLite migration, notes/relations/tables, explicit background reading, calendar/Beeper access and optional connected AI/routines. Earlier foreground-only and preferences-only descriptions below apply to the original chat/Voice path unless updated here.

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

The [iteration workflow](iteration-workflow.md) is the operational reference. Preflight runs source/resource, documentation and tool regressions. One Android job performs JVM tests, lint and both flavor builds. Errors block publication; diagnostics are kept even on failure. PR runs validate merge results without signing secrets and never publish. Manual dispatch validates without publishing. App/tooling pushes to main/improve-pixel-assistant publish after all gates pass. Known documentation-only changes run preflight and leave APKs alone; canonical draft PRs defer duplicate merge validation until ready-for-review.

Successful builds carry `release-evidence.json`, `SHA256SUMS`, reports, native screenshots and `review.html` in **aegentica-evidence.zip**. CI validates package IDs/version codes, checks source/run provenance, downloads release assets and compares hashes. Screenshot generation is not visual approval or physical Pixel certification.

A build-specific release such as `preview-improve-pixel-assistant-build-102` is published and verified before compatibility aliases are refreshed. These unique links are the preferred handoff and rollback reference. A rerun adds `-r<attempt>` to its release tag, but can reuse the Android version code. Existing published build assets are not silently replaced. Retrying a partially uploaded draft is supported.

The rolling preview tag is updated without deleting the previous release first. Its multiple asset/tag updates are not atomic; use the unique build URL when exact identity matters. If alias refresh fails, the verified unique build remains available. Branches that have advanced are skipped before publication. Main's existing distribution identity remains distinct from preview links.

Use `.apk` release assets on Android. Evidence ZIPs are for inspection, not installation. Core and Nano are both included; Nano is the assistant.

## Distribution boundary

These are debug/personal preview APKs. Publishing to Google Play requires a deliberate release build/signing strategy, device validation, current policy review and accurate product/privacy disclosures. The repository does not claim a production rollout, signing-key escrow service, or automatic cross-device migration.

Both APKs also pass Android `apksigner verify`; certificate fingerprints are retained in the evidence bundle. This proves signature integrity, not continuity with a previously installed preview key.

Release descriptions include the tracked [preview changes](preview-notes.md). Superseded validation can be cancelled, while active publishers finish under a separate serialized job group. HEAD is checked before publication and again before alias promotion; all alias assets are verified. Interrupted or out-of-order runs must still use the build-specific link for exact identity.

## Less installation friction

Read the install/update line at the top of each exact build release. It reports `private-key-configured` or `ephemeral-debug` behavior from that build, plus Nano package, version code and checksum. Evidence includes the actual public certificate fingerprints. “Private key configured” does not prove compatibility with an older installed debug key.

1. Open the new Nano APK. If Android offers **Update**, accept it; there is no reason to uninstall first.
2. Confirm the expected `3.0.<build>-nano` in Settings and that your history remains.
3. If Android rejects the update, keep the existing app installed while you export and check the backup. Only then follow the reinstall/restore procedure above. Settings/voice preferences need separate setup after uninstall.
4. Test only the changed flows for a small personal iteration; run the broader checklist before wider distribution. A previous APK is a comparison artifact, not guaranteed in-place downgrade support.

See the [whole-process audit](audits/iteration-efficiency.md) for remaining signing, default-branch and device acceptance gaps. Stable signing is the largest unresolved update friction; it requires the owner-controlled private secret setup, not more unit tests.
