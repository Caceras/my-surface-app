<p align="center">
  <img src="assets/banner.png" alt="Pixel Surface Lab" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Caceras/my-surface-app/actions/workflows/build.yml">
    <img src="https://github.com/Caceras/my-surface-app/actions/workflows/build.yml/badge.svg" alt="Build debug APKs">
  </a>
  <a href="https://github.com/Caceras/my-surface-app/releases/latest">
    <img src="https://img.shields.io/badge/download-debug--latest.apk-38BDF8" alt="Download APK">
  </a>
  <img src="https://img.shields.io/badge/AI-on--device%20only-2DD4BF" alt="On-device only">
  <img src="https://img.shields.io/badge/minSdk-29-152B3C" alt="minSdk 29">
  <img src="https://img.shields.io/badge/license-MIT-6B6B6B" alt="MIT">
</p>

---

## What this is

Select text in any app on your Pixel, tap **Summarise**, and get an answer from
a model running on the phone itself. No account, no API key, no network — turn
off wifi and mobile data and it still works.

Getting there normally means Android Studio, an SDK install, a Gradle fight and
a pile of AICore boilerplate. Here it is one script and a push. Roughly five
minutes end to end, most of it waiting for CI.

The repo is also the template underneath: **five real Android system surfaces**,
generated for your own package name, with a static checker that catches the
failures which would otherwise cost you a CI round trip each.

<p align="center">
  <img src="assets/pipeline.gif" alt="Scaffold, verify, push, install" width="720">
</p>

---

## Two builds of the same app

| | `core` | `nano` |
|---|---|---|
| Dependencies | **none** | 3 ML Kit GenAI artifacts |
| Text-selection actions | Uppercase | **Summarise, Proofread, Make professional** |
| Model | none | Gemini Nano, via Android AICore |
| APK size | ~2.6 MB | ~10 MB |
| Runs on | any Android 10+ device | supported Pixels with AICore |
| Network | never | **never** |

They install side by side (`nano` carries an `applicationIdSuffix`), so you can
put both on the phone and compare. `core` exists to prove the plumbing works
before a model is anywhere near it — and because a template that builds first
time on a machine with nothing installed is worth protecting.

Every surface talks to a single `SurfaceBrain` interface. The activities, the
tile and the widget have no idea which implementation they got.

---

## The five surfaces

<p align="center">
  <img src="assets/surfaces.png" alt="Where each surface appears on a Pixel" width="100%">
</p>

| Surface | `--surface` key | Where it appears | API |
|---|---|---|---|
| Quick Settings tile | `tile` | Shade → Edit tiles → *From apps that you installed* | 24+ |
| Home screen widget | `widget` | Long-press home → Widgets | 26+ |
| App shortcuts | `shortcuts` | Long-press the app icon | 25+ |
| Share sheet target | `share` | Share anything → app row | all |
| Text selection action | `processtext` | Select text → popup overflow | 23+ |

In the `nano` build these stop being demos: the tile reports whether Nano is
present and downloads it on tap, the widget shows the last result, and anything
shared into the app can be summarised on device.

**One activity, several menu items.** The text-selection popup shows one entry
per exported `<activity-alias>`, so three actions cost three manifest entries
and zero extra classes — the activity reads back which alias was tapped. The
aliases live in the flavour manifests, which is what makes the menu differ
between builds.

Not generated but documented in [`docs/surfaces.md`](docs/surfaces.md):
notification listener, live wallpaper, accessibility service, direct share,
deep links — plus the surfaces that **aren't** extensible (Now Playing, At a
Glance, Quick Share), which saves you going looking.

---

## Read this before you expect it to work in Swedish

The on-device models have a fixed language list and it is short:

| Task | Languages |
|---|---|
| Summarise | English, Japanese, Korean |
| Proofread / Rewrite | English, Japanese, Korean, German, French, Italian, Spanish |

No Swedish, no Nordic languages at all. The app detects likely Swedish input and
says so rather than handing back confident nonsense. If you need Swedish on
device, the path is a bundled open-weights model through MediaPipe or LiteRT-LM,
not these APIs — see [`docs/ai.md`](docs/ai.md).

Gemini Nano also needs a supported Pixel with a **locked bootloader** and a
current Android AICore. On anything else the app degrades to a clear message
instead of a crash.

---

## Quickstart

### Use this repo as-is

```bash
git clone https://github.com/Caceras/my-surface-app.git my-surface-app
cd my-surface-app
./ship.sh          # creates your own repo and pushes
```

### Or generate a fresh, smaller app

```bash
python tools/scaffold.py \
  --out ../my-tile \
  --package com.example.mytile \
  --app-name "My Tile" \
  --surface tile

python tools/verify.py ../my-tile
```

The generator produces the zero-dependency app. The AI flavour is demonstrated
here, in `app/src/nano/`.

### Get it on the phone

1. Open the repo's **Releases** page on the Pixel.
2. Tap `pixel-surface-lab-nano.apk`.
3. Allow installs from Chrome when prompted.
4. Open the app and press **Check / prepare on-device model**. First run
   downloads the feature; after that it is offline forever.
5. Select some English text anywhere and look in the popup overflow.

> **Why the release and not the artifact?** GitHub always serves workflow
> artifacts as a `.zip`, and Android will not install a zip. A release asset is
> a direct `.apk` URL. This is the single most common place this workflow dies.

The rolling `debug-latest` tag is reused on every build, so **the download URL
never changes** — bookmark `/releases/latest` on your phone once and re-use it
forever.

---

## Verify before you push

```bash
python tools/verify.py .        # two seconds
python tools/test_verify.py     # proves the checker still checks
```

A CI round trip costs about three minutes. `verify.py` needs no Android SDK and
catches the failures that would otherwise burn that trip:

- dangling `@drawable/…` / `R.string.…` references
- manifest components with no matching Kotlin source
- an `<activity-alias>` pointing at an activity that doesn't exist
- an `intent-filter` with no `android:exported` (a hard AGP error)
- a resource used from `src/main` that only some flavours define — the failure
  mode where one variant is green and the other cannot compile
- imports that need a dependency the `core` source set deliberately refuses
- a workflow missing `contents: write` (releases fail with a silent 403)

Exit code is non-zero on failure, so it chains: `python tools/verify.py . && git push`

CI runs both scripts before it will start a build.

---

## Repo layout

```
├── app/
│   └── src/
│       ├── main/         shared: surfaces, SurfaceBrain interface, storage
│       ├── core/         zero-dependency brain + its manifest entry
│       └── nano/         Gemini Nano brain + its three manifest entries
├── tools/
│   ├── scaffold.py       generates a new surface app
│   ├── verify.py         static pre-flight checks
│   ├── test_verify.py    tests for the checker
│   └── make_assets.py    regenerates the branded images
├── docs/
│   ├── ai.md             on-device AI: availability, languages, alternatives
│   ├── surfaces.md       every surface, its gotchas and constraints
│   ├── versions.md       toolchain matrix + error→fix mapping
│   └── delivery.md       signing, install prompts, private repos
├── assets/               banner, social card, diagram, GIF, fonts
└── .github/workflows/    verify → build both flavours → publish
```

---

## Design decisions

**The dependency-free core is load-bearing.** Dependency resolution is the most
common source of mystery build failures. `core` and `main` are held to framework
APIs only, and `verify.py` enforces it, so a stray `import androidx.…` fails
locally in two seconds rather than in CI in three minutes. Every dependency in
this project lives in exactly one place: the `nano` flavour.

**No model in the APK.** The GenAI APIs call Android AICore, a system service.
Nothing is bundled and nothing is downloaded by this app — which is why `nano`
is 10 MB rather than several hundred.

**No Gradle wrapper.** The workflow installs Gradle directly via
`gradle/actions/setup-gradle`, avoiding a committed binary `gradle-wrapper.jar`.
Android Studio will offer to add a wrapper if you open the project locally.

**Debug signing.** Correct for a personal test, wrong for distribution. An APK
signed with a different key cannot upgrade over an existing install — switching
between a local build and a CI build hits `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
and the fix is to uninstall first. See [`docs/delivery.md`](docs/delivery.md).

**Generated assets.** Images are produced by `tools/make_assets.py` from brand
tokens rather than checked in as opaque blobs, so the whole set re-themes by
changing a few constants.

---

## Re-theming

Edit the theme tokens at the top of `tools/make_assets.py`. To add a logo, drop
a transparent `wordmark.png` into `assets/`. Then re-run:

```bash
python tools/make_assets.py
```

The app's own colours live in `app/src/main/res/values/colors.xml` and the
launcher icon in `res/drawable/ic_launcher_foreground.xml`.

---

## When the build fails

Nearly always toolchain version skew, not broken code — Gradle, AGP, the JDK and
`compileSdk` all constrain each other, and AGP 9 changed two rules that break
every older project.
[`docs/versions.md`](docs/versions.md) maps the exact error strings to the thing
to bump. Read the Actions log first; the build runs with `--stacktrace`.

---

## Scope

These are prototypes for your own device. Play Store distribution, background
execution, or anything reading other apps' content (notification listeners,
accessibility services) is a different problem with policy constraints —
`docs/surfaces.md` flags which surfaces carry them.

---

<p align="center">
  <sub>MIT licensed · Illustrations in this README are diagrams, not device screenshots</sub>
</p>
