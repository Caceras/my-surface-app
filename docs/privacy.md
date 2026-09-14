# Privacy and data

This describes the implementation in this repository, not a legal guarantee about Android, a keyboard, a document provider, or Google's services.

## Processing

- Gemini Nano requests are passed to Android AICore through the ML Kit Prompt API. Surface has no implemented remote-model fallback or web-browsing capability.
- Dictation uses `createOnDeviceSpeechRecognizer`, gated by runtime availability. The app does not fall back to Android's general cloud-capable recognizer.
- Spoken output selects a matching installed voice that does not require the network. If none qualifies, the response remains available as text with an explanation.
- The app does not save microphone audio buffers. Recognized text can become a draft or conversation turn.
- Surface implements no analytics, account service, remote chat sync, or advertising SDK. Initial downloads and the behavior of system/SDK services remain outside that narrower statement.

Google documents [on-device GenAI processing and service constraints](https://developers.google.com/ml-kit/genai). Speech behavior is also governed by the installed Android recognition and TTS services.

## Retained data

| Data | Location | Retention / removal |
|---|---|---|
| Current chat | App-private preferences | Newest 40 completed exchanges; New moves current content into recent archives |
| Current draft | App-private preferences / activity state | Replaced as the conversation changes; persisted when leaving |
| Recent conversations | App-private preferences | Up to 12; soft size budget may evict older entries; delete individually or clear saved |
| Last successful answer | App-private preferences | Replaced by the next saved result or cleared by New |
| Speech preferences | App-private preferences | Persist until changed or app data is cleared |
| Manual export | User-selected document provider | Plain JSON; deletion and syncing belong to the chosen provider |

Uninstalling or clearing app storage removes local app data. Android automatic backup is disabled with `allowBackup="false"`. Cross-device transfer behavior should still be checked on the target Android/device combination. The app does not add a separate encryption layer to its preferences or exports; Android provides the app sandbox and device storage protection.

## Visible surfaces

The home-screen widget displays a preview of the last saved answer. Anyone able to view that home screen may see it. Remove the widget if that is unsuitable. Copy uses Android's clipboard; Share uses the app you choose. The keyboard you use has its own behavior and settings.

Surface cannot silently inspect the screen or read arbitrary app contents. Share and text-selection entry points receive the text explicitly supplied by Android/the source app. The app does not implement an accessibility service, notification listener, background microphone service, or wake-word detector.

## Permissions

The app's source manifest requests microphone access for voice capture. The microphone is requested when entering an actual recording flow, and setup alone does not start capture. File import/export uses Android's document picker instead of broad storage access. Widget pinning, tile addition and default-assistant selection use Android-managed user choices.

Review the merged manifest and dependencies when changing SDK versions: library manifests may contribute declarations that are not visible in the app's source manifest alone.

## Reporting safely

Use redacted screenshots, dummy conversations, and the build number when reporting bugs. Do not attach a full export to a public GitHub issue if it contains personal or client information. No vulnerability-reporting SLA or production security certification is claimed for this preview.
