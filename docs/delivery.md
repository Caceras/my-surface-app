# Getting the APK onto the phone

## The iteration loop

The point of all of this is a short loop: say what should change, and try it on
the phone. Everything below exists to keep that loop to one tap at your end.

```
you say what to change
      │
      ▼
change lands on main ──► CI: verify, test, build both flavours (~3 min)
                                        │
                                        ▼
                          rolling release "debug-latest" is replaced
                                        │
                                        ▼
              phone bookmark /releases/latest ──► tap the .apk ──► install
```

Three things make it survivable:

- **The download URL never changes.** One rolling tag, reused. Bookmark
  `/releases/latest` on the phone once and never think about it again.
- **A broken change never reaches the phone.** The release job needs `build`
  *and* `test` to pass first, so a red build simply leaves the previous APK in
  place.
- **The build number is on screen.** `versionName` carries the CI run number,
  the launcher screen shows it, and the release is titled with the same number.
  After installing you can see whether the install actually took, which is the
  one question a rolling tag makes hard to answer.

An install over the top keeps your data and settings; it is an upgrade, not a
fresh install, as long as the signing key does not change (see **Signing**).

## Why the release, not the artifact

The workflow publishes the APK twice, and the distinction matters more than it
looks.

A **workflow artifact** is always served as a `.zip`, even for a single file.
GitHub has no option to change this. On a phone, that zip lands in Downloads and
Android will not install it — the user has to find a file manager with an
extractor, unzip it, then locate the APK. Several minutes of confusion at the
exact moment the thing was supposed to be finished.

A **release asset** is served at its own URL with its original filename. Tapping
it in Chrome triggers the package installer directly. That is the path to give
the user.

The artifact upload is kept anyway because it is useful from a desktop and
survives even if release creation fails due to permissions.

## Required workflow permissions

Creating a release from CI needs:

```yaml
permissions:
  contents: write
```

Without it, `gh release create` fails with a 403 while the build itself passes —
a confusing state, because the log looks mostly green. If the repo or
organisation sets default workflow permissions to read-only, that setting
overrides the workflow block and has to be changed in repo settings under
Actions → General → Workflow permissions.

## First-install prompts on a Pixel

The user will hit a permission gate the first time. Chrome needs "Allow from
this source" for installing unknown apps, granted per-source in Settings → Apps
→ Special app access. Modern Android routes this inline — a prompt appears with
a link to the right settings screen and returns afterwards.

Play Protect may also warn about an unrecognised developer. For a self-built
debug APK this is expected and the user can proceed.

## Signing

Debug builds are signed automatically with a generated debug keystore. This is
fine for a personal test and requires no configuration.

Two consequences to be aware of:

**Upgrades require the same key.** An APK signed with a different key cannot
install over an existing one; it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
and the only fix is uninstalling first. Because each machine and each CI run may
have a different debug keystore, mixing local and CI builds of the same package
hits this regularly.

**Debug builds are not distributable.** They are debuggable, unoptimised, and
signed with a key that is not secret. If the app is going to anyone else:

1. Generate a keystore with `keytool`.
2. Store it base64-encoded as a repository secret, along with the passwords.
3. Decode it in the workflow and configure `signingConfigs` in the app module.
4. Build `assembleRelease` instead.

That is a meaningfully different setup, and the point at which the throwaway
framing in this skill stops applying.

## Private repos

Release assets in a private repo require authentication to download, so tapping
the link on a phone that is not signed into GitHub will fail. Either sign into
GitHub in the phone's browser, or use a public repo for throwaway experiments.

## Alternatives to GitHub Actions

If the user does not want a repo at all:

- **Android Studio over USB** — fastest iteration loop once set up, but the
  setup is the thing this skill exists to avoid.
- **`gradle assembleDebug` locally** — needs a JDK and the Android SDK
  installed; the APK lands in `app/build/outputs/apk/debug/`.

Both are reasonable if the user already has an Android environment. If they do
not, the CI route stays faster overall even accounting for the wait.
