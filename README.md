# Ægentica AI

A native Android workspace for notes, AI chat, voice and planning.

**[Preview releases](https://github.com/Caceras/my-surface-app/releases)** · [Everyday guide](docs/everyday-workspace.md) · [Phone setup](docs/getting-started.md) · [Current changes](docs/preview-notes.md)

The Today · AI · Library implementation from PR #7 is now merged into `main`. Releases are personal previews, not Play Store releases. Prefer the build-specific Nano download supplied with a verified release, rather than an old development-branch alias.

## Install

Download `aegentica-ai-nano.apk` from the relevant build release and open it on the phone. Use **Update** when Android offers it. Check the build number in Settings. The `core` APK is a deterministic test/demo variant, not the AI assistant.

**Export your workspace before any uninstall.** Preview signing can differ between builds. If Android reports a signature conflict, preserve your data before uninstalling, then install and restore. [Signing and update guide](docs/delivery.md).

For on-device AI, use **Check / prepare on-device model**. Initial model and speech downloads require a connection. Voice setup chooses the recognition/playback language and offers a speaker test. Runtime checks report unsupported devices or unavailable AICore; a passing CI build does not establish that Nano is ready on a particular phone.

## Workspace

**Today** contains linked tasks, reminders, routines and an optional selected-calendar agenda. **AI** contains typed chat, editable dictation, foreground voice conversations and explicitly selected reference records. **Library** contains autosaved notes, originals, search, pinning, Trash/undo, people/projects, backlinks, collections and typed table fields.

Read-aloud supports sentence position, speed, pause/resume and background media/headset controls. It selects a high-quality installed offline voice for the chosen language. This uses the phone's speech engine; it does not promise a new neural voice. The chat formatter supports bold, italics, lists and code, with display-frame-coalesced updates and no welcome slogans.

Android entry points include the launcher, widget, Quick Settings tile, shortcuts, share sheet and text-selection actions. Explicit handoffs can open supported Clock, Calendar, Maps and Dialer actions. The app does not implement a wake word, autonomous phone control, web browsing or background Nano inference.

## Connections

Notes, Gemini Nano and offline speech need no cloud account. Connected AI is separately configured with an HTTPS endpoint, model and encrypted credential. It is never a silent fallback. Scheduled connected generation requires separate routine approval and keeps a local execution ledger.

Calendar and experimental Beeper access are optional and permission-gated. Beeper sends require review of the exact recipient and text. Real provider requests, account compatibility and background timing require device/account validation. No new paid speech provider is introduced by the presentation repair.

## Data

Notes, links, typed fields, chats and drafts are stored in app-private SQLite. Current chat/history retention is bounded; notes are not automatically evicted. Uninstalling removes local data. Whole-workspace JSON backups exclude credentials but can contain sensitive text, so choose their storage location carefully. Legacy conversation imports remain supported.

Widget content previews are off by default. Private screen can hide Recents previews and block screenshots. No app analytics service is added. System services manage initial AI/speech downloads under their own policies. [Privacy and retention details](docs/privacy.md).

## Build

Kotlin and Android framework views; no WebView or Compose replacement. Main/core have no third-party UI or AI runtime dependencies; Nano adds ML Kit. The project has no Gradle wrapper.

Pinned toolchain: JDK 21, Gradle 9.7.1, Android SDK/build-tools 36 and AGP 9.3.2. [Version reference](docs/versions.md).

```bash
git clone https://github.com/Caceras/my-surface-app.git
cd my-surface-app
python tools/check.py
gradle testCoreDebugUnitTest lintCoreDebug lintNanoDebug assembleCoreDebug assembleNanoDebug --no-daemon
```

The Nano package is `com.caceras.surface.nano`; core is `com.caceras.surface`. APKs appear under `app/build/outputs/apk/{core,nano}/debug/`.

CI runs preflight, JVM tests, lint and both Android builds, verifies signatures and packages source-linked evidence. Eligible push runs publish build-specific links and compare uploaded/downloaded hashes. Tests and screenshots use core fixtures; they cannot certify real Nano output, voice quality, frame timing, battery or signing continuity on a phone.

## Development

[Architecture](docs/architecture.md) · [Testing](docs/testing.md) · [Iteration workflow](docs/iteration-workflow.md) · [Contribution guide](CONTRIBUTING.md) · [Presentation repair](docs/audits/2026-09-18-presentation.md)

For feedback, use **Settings → Copy app info**, then provide the failed step and a redacted screenshot. The app does not upload a report or your conversations automatically.

[MIT license](LICENSE).
