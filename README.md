# Ægentica AI

**A little clarity. A little possibility.**

A native Android assistant for typing, talking, thinking things through, and opening useful actions on your phone. Ægentica AI uses Gemini Nano through Android AICore, with on-device dictation and spoken replies. No account or API key is required.

[![Android checks](https://github.com/Caceras/my-surface-app/actions/workflows/build.yml/badge.svg?branch=improve-pixel-assistant)](https://github.com/Caceras/my-surface-app/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-70CEFA.svg)](LICENSE)

**[Download the Nano preview APK](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant/aegentica-ai-nano.apk)** · [What changed](docs/preview-notes.md) · [Phone setup](docs/getting-started.md) · [Android plan](docs/android-native-plan.md) · [Quality audit](docs/cohesion-audit.md) · [Iteration workflow](docs/iteration-workflow.md) · [Contribute](CONTRIBUTING.md)

> This is the **Ægentica AI** app on `improve-pixel-assistant`. The main branch and `/releases/latest` may contain an older experience. This preview is not a Play Store release. Export your conversations before any reinstall.

## The experience

- **Write naturally.** A roomy composer, editable dictation, and conversation context for follow-up questions.
- **Talk and listen.** Pause to send in Voice, hear sentences as they become available, and enable **Keep talking** for a continuous foreground session.
- **Stay in control.** Stop generation, quiet playback, copy or share answers, and read earlier messages without chat pulling you to the bottom.
- **Return to a thought.** New saves the conversation you leave. Search saved conversations, inspect previews, resume, or delete individual entries.
- **Do the everyday.** Set a timer or alarm, draft a calendar event, find a place, or open the dialer through Android apps.
- **Make it yours.** Pin Chat/Voice, resize the widget, choose answer previews, and enable Private screen.
- **Feel at home.** Sky-blue light and dark themes, readable formatting, restrained motion, and a composer that follows the keyboard transition.

<p>
  <img src="docs/images/chat.png" alt="Ægentica AI chat with starters and a spacious composer" width="260">
  <img src="docs/images/voice.png" alt="Voice response with quiet and stop controls" width="260">
  <img src="docs/images/chat-night.png" alt="Ægentica AI chat in dark mode" width="260">
</p>

Curated native framework illustrations from the original Ægentica AI branding pass (see the [audit](docs/quality-audit.md#evidence)), using the deterministic core test fixture. These are core test fixtures, not real Nano responses or Pixel system chrome. For current screens, download the release’s **aegentica-evidence.zip** and open **review.html**.

## Try it on a Pixel

1. Download **`aegentica-ai-nano.apk`** from the preview link above and open it on the phone. Allow installation from your browser if Android asks.
2. Open **Ægentica AI**. Check the build number in **Settings** against the [preview release](https://github.com/Caceras/my-surface-app/releases/tag/preview-improve-pixel-assistant).
3. Select **Check / prepare on-device model**. Initial model and speech downloads need an internet connection.
4. Type a short question. Then open **Settings → Set up voice & test playback** to choose your language, download offline speech, and test the speaker.
5. Tap **Voice**, speak, and pause. Use **Type** to return to the shared chat. Enable **Keep talking** only when you want the microphone to reopen after the reply.

If an update reports a package/signature conflict, **export first**, uninstall the existing Ægentica AI, install the new APK, then restore. Do not uninstall to troubleshoot before preserving the data you want. [Update and signing guide](docs/delivery.md).

The Pixel 10 Pro XL is listed in Google's Prompt API support table. Availability still depends on the device configuration and AICore readiness. The app checks readiness at runtime. [Google's current device support](https://developers.google.com/ml-kit/genai#device_support).

## Within reach

| Entry point | What happens | Setup |
|---|---|---|
| App | Opens typed chat and the current draft | Open Ægentica AI |
| Voice | Opens the foreground voice conversation | Chat → Voice |
| Pixel Quick Tap | Opens chat with a back-of-phone double tap | Android Settings → System → Gestures → Quick Tap → Open app |
| Assistant gesture | Opens Voice where Android supports the selected assistant | Settings → Set as digital assistant |
| Quick Settings | Opens Voice, with setup and Type available | Add the app tile from Android's tile editor |
| Home widget | Type/Talk; last-answer preview only when enabled | Settings → Add home screen widget |
| Launcher shortcuts | Chat, Voice, History and Actions; Chat/Voice can be pinned | Long-press the launcher icon |
| Share sheet | Stages shared **plain text** in the composer for review | Share text → Ægentica AI |
| Text selection | Ask, Summarise, Proofread, or Make professional in the Nano build | Select text in a supporting app → overflow menu |

Ægentica AI does not implement a wake word, autonomous phone automation, background inference, web browsing, Wear OS, or Android Auto. It sees other apps' text only when explicitly shared or selected. [Ægentica AI behavior and limits](docs/surfaces.md).

## Private, with clear boundaries

Inference and speech use on-device APIs. Ægentica AI does not implement a cloud fallback or its own analytics service. System services still manage downloads and have their own policies; “on-device” does not mean the phone never uses a network.

Chats and drafts are stored in app-private preferences. Up to 40 completed exchanges are kept in the current chat, and up to 12 recent conversations are archived within a soft size budget. Uninstalling removes local data. Manual exports are readable JSON and can include sensitive text; the file location and any syncing are controlled by the document provider you choose. Widget answer previews are off by default. Private screen can hide Recents previews and block screenshots; clipboard copies carry sensitive-preview metadata. [Privacy and data guide](docs/privacy.md).

## Build and test

The project is Kotlin with Android framework views. `main` and `core` declare no third-party UI or AI libraries; `nano` adds ML Kit. There is no Gradle wrapper.

**Pinned toolchain:** JDK 21, Gradle 9.7.1, Android SDK/build-tools 36, AGP 9.3.2. [Version reference](docs/versions.md).

```bash
git clone --branch improve-pixel-assistant https://github.com/Caceras/my-surface-app.git
cd my-surface-app
python tools/check.py
gradle testCoreDebugUnitTest lintCoreDebug lintNanoDebug assembleCoreDebug assembleNanoDebug --no-daemon
```

| Variant | Purpose | Application ID |
|---|---|---|
| `nano` | The actual Gemini Nano assistant | `com.caceras.surface.nano` |
| `core` | Deterministic demo and UI tests; uppercase output, no AI model | `com.caceras.surface` |

APKs appear under `app/build/outputs/apk/{core,nano}/debug/`. Native screenshots appear under `app/build/screenshots/`. CI runs one shared Android job for JVM tests, lint and both builds. Successful runs generate a source-linked evidence bundle and verified build-specific APK links; failed runs retain diagnostics. Tests exercise the core variant, so green CI does **not** establish Nano quality, real speech latency, or device gesture behavior. [Testing and device checklist](docs/testing.md).

## Documentation

| Guide | Covers |
|---|---|
| [Iteration workflow](docs/iteration-workflow.md) | Pipeline audit, automated evidence, verified releases and feedback loop |
| [Cohesion audit](docs/cohesion-audit.md) | Current cross-flow fixes, fresh baseline and remaining device gates |
| [Android capability plan](docs/android-native-plan.md) | 40+ capability areas, delivery contracts, follow-on priorities and gates |
| [Native actions](docs/native-actions.md) | Clock, Calendar, Maps, Dialer and keyboard/shortcut behavior |
| [Design system](docs/design-system.md) | Æ identity, sky-blue tokens, icon rules and accessibility |
| [Getting started](docs/getting-started.md) | Installation, daily controls, speech setup, and common problems |
| [Architecture](docs/architecture.md) | UI boundaries, generation lifecycle, streaming, and storage |
| [Privacy](docs/privacy.md) | Permissions, retained data, exports, and deletion |
| [Testing](docs/testing.md) | Reproducible checks and the physical-device release checklist |
| [Quality audit](docs/quality-audit.md) | Evidence, fixed issues, and remaining risks |
| [Delivery](docs/delivery.md) | Preview releases, updates, signing, and Play distribution limits |
| [Voice](docs/voice.md) | Recognition, speech output, cancellation, and interruption |
| [AI](docs/ai.md) | Prompt construction, AICore readiness, and model boundaries |
| [Surfaces](docs/surfaces.md) | Android entry points and unsupported integrations |

The original surface generator remains available in `tools/scaffold.py`; it generates a minimal framework app, not this complete assistant. [Contributor guide](CONTRIBUTING.md).

## Status

An actively developed personal preview, not a claim of production readiness or guaranteed model accuracy. Hardware validation is required before wider distribution. Issues and focused contributions are welcome. Use **Settings → Help & feedback → Copy app info**, then include the steps to reproduce and redacted screenshots. **MIT licensed.**
