# Surface

**A little clarity. A little possibility.**

A native Android assistant for typing, talking, and thinking things through. Surface uses Gemini Nano through Android AICore, with on-device dictation and spoken replies. No account or API key is required.

[![Android checks](https://github.com/Caceras/my-surface-app/actions/workflows/build.yml/badge.svg?branch=improve-pixel-assistant)](https://github.com/Caceras/my-surface-app/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-284D3E.svg)](LICENSE)

**[Download the Nano preview APK](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant/pixel-surface-lab-nano.apk)** · [Phone setup](docs/getting-started.md) · [Quality audit](docs/quality-audit.md) · [Contribute](CONTRIBUTING.md)

> This is the **Surface Preview** app on `improve-pixel-assistant`. The main branch and `/releases/latest` may contain an older experience. This preview is not a Play Store release. Export your conversations before any reinstall.

## The experience

- **Write naturally.** A roomy composer, editable dictation, and conversation context for follow-up questions.
- **Talk and listen.** Pause to send in Voice, hear sentences as they become available, and enable **Keep talking** for a continuous foreground session.
- **Stay in control.** Stop generation, quiet playback, copy or share answers, and read earlier messages without chat pulling you to the bottom.
- **Return to a thought.** New saves the conversation you leave. Search saved conversations, inspect previews, resume, or delete individual entries.
- **Feel at home.** Warm light and dark themes, readable formatting, restrained motion, and a composer that follows the keyboard transition.

<p>
  <img src="docs/images/chat.png" alt="Surface chat with starters and a spacious composer" width="260">
  <img src="docs/images/voice.png" alt="Voice response with quiet and stop controls" width="260">
  <img src="docs/images/chat-night.png" alt="Surface chat in dark mode" width="260">
</p>

Native framework renders from [source e148efa](https://github.com/Caceras/my-surface-app/commit/e148efaa8f339e64dedb68e25f2fc4bd97686af3), using the deterministic core test fixture. These show the actual layouts, not real Nano responses or Pixel system chrome.

## Try it on a Pixel

1. Download **`pixel-surface-lab-nano.apk`** from the preview link above and open it on the phone. Allow installation from your browser if Android asks.
2. Open **Surface Preview**. Check the build number in **Settings** against the [preview release](https://github.com/Caceras/my-surface-app/releases/tag/preview-improve-pixel-assistant).
3. Select **Check / prepare on-device model**. Initial model and speech downloads need an internet connection.
4. Type a short question. Then open **Settings → Set up voice & test playback** to choose your language, download offline speech, and test the speaker.
5. Tap **Voice**, speak, and pause. Use **Type** to return to the shared chat. Enable **Keep talking** only when you want the microphone to reopen after the reply.

If an update reports a package/signature conflict, **export first**, uninstall the existing Surface Preview, install the new APK, then restore. Do not uninstall to troubleshoot before preserving the data you want. [Update and signing guide](docs/delivery.md).

The Pixel 10 Pro XL is listed in Google's Prompt API support table. Availability still depends on the device configuration and AICore readiness. The app checks readiness at runtime. [Google's current device support](https://developers.google.com/ml-kit/genai#device_support).

## Within reach

| Entry point | What happens | Setup |
|---|---|---|
| App | Opens typed chat and the current draft | Open Surface Preview |
| Voice | Opens the foreground voice conversation | Chat → Voice |
| Assistant gesture | Opens Voice where Android supports the selected assistant | Settings → Set as digital assistant |
| Quick Settings | Opens Voice, with setup and Type available | Add the app tile from Android's tile editor |
| Home widget | Shows the last result; Type and Talk open the app | Settings → Add home screen widget |
| Launcher shortcuts | Direct chat/task/voice entry, depending on build and Android version | Long-press the launcher icon |
| Share sheet | Stages shared **plain text** in the composer for review | Share text → Surface Preview |
| Text selection | Ask, Summarise, Proofread, or Make professional in the Nano build | Select text in a supporting app → overflow menu |

Surface does not implement a wake word, general phone automation, background inference, web browsing, Wear OS, or Android Auto. It sees other apps' text only when explicitly shared or selected. [Surface behavior and limits](docs/surfaces.md).

## Private, with clear boundaries

Inference and speech use on-device APIs. Surface does not implement a cloud fallback or its own analytics service. System services still manage downloads and have their own policies; “on-device” does not mean the phone never uses a network.

Chats and drafts are stored in app-private preferences. Up to 40 completed exchanges are kept in the current chat, and up to 12 recent conversations are archived within a soft size budget. Uninstalling removes local data. Manual exports are readable JSON and can include sensitive text; the file location and any syncing are controlled by the document provider you choose. The home widget can show the last answer. [Privacy and data guide](docs/privacy.md).

## Build and test

The project is Kotlin with Android framework views. `main` and `core` declare no third-party UI or AI libraries; `nano` adds ML Kit. There is no Gradle wrapper.

**Pinned toolchain:** JDK 21, Gradle 9.7.1, Android SDK/build-tools 36, AGP 9.3.2. [Version reference](docs/versions.md).

```bash
git clone --branch improve-pixel-assistant https://github.com/Caceras/my-surface-app.git
cd my-surface-app
python tools/verify.py .
python tools/test_verify.py
python tools/check_docs.py .
gradle testCoreDebugUnitTest assembleCoreDebug assembleNanoDebug --no-daemon
```

| Variant | Purpose | Application ID |
|---|---|---|
| `nano` | The actual Gemini Nano assistant | `com.caceras.surface.nano` |
| `core` | Deterministic demo and UI tests; uppercase output, no AI model | `com.caceras.surface` |

APKs appear under `app/build/outputs/apk/{core,nano}/debug/`. Native screenshots appear under `app/build/screenshots/`. CI runs static checks, JVM tests, and both builds; preview publishing requires the test and build jobs to pass. Tests exercise the core variant, so green CI does **not** establish Nano quality, real speech latency, or device gesture behavior. [Testing and device checklist](docs/testing.md).

## Documentation

| Guide | Covers |
|---|---|
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

An actively developed personal preview, not a claim of production readiness or guaranteed model accuracy. Hardware validation is required before wider distribution. Issues and focused contributions are welcome; include the build number, steps to reproduce, and redacted screenshots. **MIT licensed.**
