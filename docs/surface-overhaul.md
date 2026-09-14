# Surface Preview 3 — Android overhaul

> Historical record of an earlier implementation pass. For current behavior and open risks, use the [quality audit](quality-audit.md), [README](../README.md), and [phone guide](getting-started.md).
This is the redesign of Pixel Surface Lab in PR #7. It remains a native Android app using framework UI and on-device Gemini Nano. The new launcher name is **Surface Preview**. Its Nano package is `com.caceras.surface.nano`, so it can be installed alongside the older `com.caceras.surfacelab.nano` app.

## Audit evidence

The initial audit used the two supplied phone screenshots (8110.png and 8109.png), the current source, manifest, resources, tests, and build workflow. The screenshots were viewed directly; they were not published to this public repository. The installed build number was not visible, so they cannot establish which previous commit the phone was running. Physical Pixel testing is still required for AICore, recognition packs, audio quality, assistant gestures, and launcher behavior.

1. **Chat launch — high severity.** Screenshot 8110 shows an almost empty screen, weak hierarchy, low-contrast system-bar icons, incomplete suggestion fragments and no inviting voice entry. The source branch already contained a greeting that was absent in the screenshot; an outdated installation is plausible, but unproven. The redesign adds a measurable welcome layout, a distinctive existing brand mark, two complete editable starter prompts, a direct voice entry, warm light and dark palettes, and explicit system-bar icon appearance.
2. **Voice failure — high severity.** Screenshot 8109 shows a missing-English-pack error with only download and close. Model readiness and speech readiness were conflated. The new screen keeps Type and Voice setup accessible. Setup explains the three independent requirements: model, speech recognition language, and installed offline playback voice. Download progress, scheduled downloads and confirmed success are different states.
3. **Regional language mismatch — high severity, source finding.** Requesting the system locale verbatim can ask for an unavailable English regional pack. Setup now queries installed and downloadable packs, prefers an exact tag then the same language, and persists the supported regional tag for recognition and playback. It never substitutes an unrelated language or cloud recognition.
4. **Microphone lifetime — high severity, source finding.** Recognition results and errors left the client connected until a later cancellation. Terminal callbacks now destroy it immediately. Setup clients have their own cancellation generation and timeout and are destroyed when the activity leaves.
5. **Reply controls — medium severity.** Long-press-only actions were difficult to discover. Answers now expose Listen, Copy and Share. Text input, editable dictation, stop-generation, shared voice/chat history, streaming speech, and opt-in continuous conversation remain.
6. **Settings and reachability — medium severity.** Settings are now a dedicated full-height, scrollable dialog. Assistant-role selection, Quick Settings tile, widget pin request, app shortcuts, share intake and text-selection actions are available. The widget and tile always open voice with setup and Type available if speech is missing.
7. **Installation identity — high severity.** Fresh debug keys can prevent upgrades and leave an old build installed. Version 3 has a separate application identity and launcher name. Settings show the exact build. Export/restore protect conversations during later reinstalls. The workflow supports private signing secrets for consistent future upgrades; no private key is committed or provisioned by this change.

## Try it on the Pixel

1. Download the **nano APK** from the [branch preview](https://github.com/Caceras/my-surface-app/releases/tag/preview-improve-pixel-assistant). Open the APK on the phone and follow Android's installer. Open **Surface Preview**, not the older Pixel Surface Lab icon. Settings must show version **3.0** and the release's build number.
2. In Settings, prepare the on-device model. The first model and speech downloads require a connection. Once installed, requests and speech use on-device engines.
3. Open **Set up voice & test playback**. Choose your speaking language; for English, try English (United States). Download offline speech, then use Test speaker in Voice options. Android speech settings are linked if the device needs an installed playback voice.
4. Tap **Talk** and pause to send. Enable **Keep talking** for a back-and-forth session. Use Quiet voice to keep reading or Stop speaking to end playback; leaving the voice screen stops the microphone. **Type** returns to the shared conversation. The chat microphone dictates an editable draft; Send submits it.
5. In Settings, set Surface Preview as the digital assistant, add its Quick Settings tile, and add its home-screen widget. Long-press its launcher icon for shortcuts. Android chooses which assistant gestures are supported by the device's configuration.

## Verification and boundaries

CI builds both core and Nano APKs and runs Robolectric tests against the deterministic core flavor. Native screenshots cover empty chat, conversation, settings, voice setup and dark mode; these are real activity layouts, not proof of Pixel hardware behavior. Regression tests cover visible greeting/voice entry, compact settings, keyboard send, visible answer actions, shared history, cancellation, offline-only TTS, language selection, mic release and backup validation.

This is a private conversational assistant, not a phone automation agent. It cannot silently read other apps, send messages, browse live information, control alarms, or listen for an always-on wake word. Text explicitly shared or selected is the cross-app context. Android Auto, Wear OS, notification replies and privileged lock-screen/hotword integration are not implemented.

## Interaction refinement

The chat composer now separates writing from dictation and uses one quiet status
line. Voice and Conversations remain available after the welcome screen leaves.
Two complete starter prompts replace the duplicate horizontal suggestion strip.
Replies animate in briefly, and the presence mark reflects thinking/listening/
speaking; Android's disabled-animation preference is respected.

Streaming preserves the reader's position. Latest reply returns to the bottom.
New saves the conversation being left; Conversations resumes it and preserves
any current draft. Up to 12 recent conversations are retained, with a soft
512 KB archive budget (the newest conversation is retained in full). Exports
include these conversations and still read older single-conversation backups.
Clear saved removes the archived conversations after confirmation.

Voice uses explicit quiet/stop controls. Scrolling and toggling Keep talking no
longer hush playback or change a button's action midway through a touch.
Validation uses framework layout and speech lifecycle tests plus native Skia
screenshots. Real Gemini Nano, speech language downloads, and perceived latency
still require a physical supported Pixel.
