# Privacy and data

This describes the implementation in this repository, not a legal guarantee about Android, a keyboard, a document provider, or Google's services.

## Processing

- Gemini Nano requests are passed to Android AICore through the ML Kit Prompt API. Ægentica AI has no implemented remote-model fallback or web-browsing capability.
- Dictation uses `createOnDeviceSpeechRecognizer`, gated by runtime availability. The app does not fall back to Android's general cloud-capable recognizer.
- Spoken output selects a matching installed voice that does not require the network. If none qualifies, the response remains available as text with an explanation.
- The app does not save microphone audio buffers. Recognized text can become a draft or conversation turn.
- Ægentica AI implements no analytics, account service, remote chat sync, or advertising SDK. Initial downloads and the behavior of system/SDK services remain outside that narrower statement.

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

Uninstalling or clearing app storage removes local app data. Android automatic backup is disabled with `allowBackup="false"`. Explicit backup and device-transfer rules also exclude preference, file and database contents. Vendor/device behavior still needs verification. The app does not add a separate encryption layer to its preferences or exports; Android provides the app sandbox and device storage protection.

## Visible surfaces

The home-screen widget defaults to generic text and Type/Talk controls. Last-answer previews require an explicit setting; compact widgets omit the preview. Anyone able to view an enabled home-screen preview may read it. Private screen uses FLAG_SECURE for app activities and dialogs, protecting Recents previews and blocking screenshots/casting of those windows. It is not encryption or protection for another app opened through a handoff. Copy uses Android's clipboard with sensitive-preview metadata; Share uses the app you choose. The keyboard you use has its own behavior and settings.

Ægentica AI cannot silently inspect the screen or read arbitrary app contents. Share and text-selection entry points receive the text explicitly supplied by Android/the source app. The app does not implement an accessibility service, notification listener, background microphone service, or wake-word detector.

## Permissions

The source manifest requests microphone access for voice capture and the normal SET_ALARM permission for explicit Clock handoffs. It does not request direct contacts, call, location or calendar access. The microphone is requested when entering an actual recording flow, and setup alone does not start capture. File import/export uses Android's document picker instead of broad storage access. Widget pinning, tile addition and default-assistant selection use Android-managed user choices.

Review the merged manifest and dependencies when changing SDK versions: library manifests may contribute declarations that are not visible in the app's source manifest alone.

## External actions

Maps receives the place query, the dialer receives the number, Calendar receives the event title, and Clock receives the chosen time/duration. This occurs only after the corresponding action in the visible UI. Destination apps have their own policies and may use the network. Calendar/dialer actions stage editable UI; Clock handlers may apply a requested timer/alarm when opened. The model cannot invoke these actions itself.

Speech's foreground MediaSession publishes only a generic Ægentica AI label, not the answer text. No foreground service, permanent notification or background microphone is added. The widget updates on result changes, settings changes and launcher resize/update events instead of periodic polling.

## Reporting safely

Use redacted screenshots, dummy conversations, and the build number when reporting bugs. Do not attach a full export to a public GitHub issue if it contains personal or client information. No vulnerability-reporting SLA or production security certification is claimed for this preview.

Search and native action fields request the same no-personalized-learning IME flag as the composer. Settings, History and Actions stop hidden microphone/generation work before opening. CI evidence uses deterministic fixture text and never captures your live conversations.
