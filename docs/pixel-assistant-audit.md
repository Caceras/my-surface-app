# Pixel assistant audit — 14 September 2026

Repository: `Caceras/my-surface-app`. Audited baseline: `d462697` (main).
Target device: Pixel 10 Pro XL. Changes: branch `improve-pixel-assistant`, PR #7.

## Findings and changes

| Priority | Finding in baseline | Change |
|---|---|---|
| High | TTS chose any offline voice, then `setLanguage()` could replace it with an unchecked default. Missing offline voices still reached `speak()`. | Match an installed offline voice to the requested language, set that exact voice, and fail visibly without a network fallback. |
| High | Voice requests contained no conversation history and saved only to the widget. | Voice uses the same bounded conversation history as chat and appends successful exchanges. Text-selection Ask also saves an exchange. |
| High | New chat during generation left the old request running; its callback could repopulate cleared storage. | Invalidate callbacks, cancel the inference future, clear the widget, and reset microphone/draft state. |
| High | TTS completion between streamed sentences could expose Talk before generation finished. Late utterance callbacks could settle a newer reply. | Track unique active utterances, require end of generation before turn completion, and ignore stale callbacks. |
| High | Paused selection screens could retain the microphone; background results could change history. | Release microphone/speech on pause, cancel foreground generation, and reject late callbacks. |
| Medium | No visible stop-generation control or recovery of interrupted prompts. | Send becomes Stop while generating. Preserve the question for editing/resending, including when leaving the app. |
| Medium | Typed responses could not be read aloud on demand. | Read-last-answer control, long-press answer actions, and optional read-every-reply preference. |
| Medium | Voice mode had no typing escape; missing recognition left a dead end. | Type opens the shared chat. The widget has explicit Type and Talk actions, and the tile falls back to chat. |
| Medium | Voice card used a zero-height weighted transcript within an unbounded card. | Bound the card height and reserve scrolling space; add a layout regression test. |
| Medium | Repeated intents, duplicate recognizer finals, microphone errors, and failed TTS startup were not consistently handled. | Recognizer session guards, startup failure messages, `onNewIntent` handling, and queued-speech cleanup. |
| Medium | Drafts were lost on navigation/recreation and dictation overwrote existing typed material. | Persist drafts, restore state, append dictated text, and stage shares without automatically sending. |
| Medium | Stream errors after a partial answer were reported as successful complete answers. | Preserve failure status and exclude failed replies from history/widget storage. |
| Medium | App was absent from the standard assistant activity entry point. | Add `ACTION_ASSIST` and `ACTION_VOICE_COMMAND`; offer Android's assistant-role chooser. |
| Medium | Small touch targets, duplicate template shortcuts, and unconditional streaming scroll disrupted use. | 48dp primary controls, one Chat shortcut plus Talk, and scroll-follow only while already at the bottom. |
| Medium | No deliberate multi-turn voice loop or speech-language selection. | Session-only Keep talking toggle; stop on silence, interruption or pause. System/English/Swedish language choices. |
| Medium | TTS had no audio-focus handling or input-length guard. | Request transient speech focus, stop on focus loss, release focus, and split requests at the engine limit. |

## Surface map

| Surface | How to reach it | Behavior / limits |
|---|---|---|
| App / keyboard | Open Pixel Surface Lab | Shared chat; editable dictation; keyboard Send or Ctrl+Enter; Shift/normal newline remains available. |
| Quick Settings | More → Add tile, or shade → Edit | Opens foreground voice, or chat when offline recognition is unavailable. Secure lock screen requires unlock. |
| Home widget | Long-press home → Widgets → Pixel Surface Lab | Last answer; Type opens chat, Talk opens voice. Two-row minimum preserves controls. |
| Launcher shortcuts | Long-press app icon | Chat and, on API 31+, Talk. Recognition availability still depends on the installed service. |
| Share sheet | Share text → Pixel Surface Lab | Adds text to an editable draft without sending. Does not fetch the contents of a shared URL. |
| Text selection | Select text → overflow → Ask Nano / preset | Ask can use dictation; results render Markdown and support copy/replace. Successful Ask exchange is available in chat. |
| Android assistant | More → Set as digital assistant | Requests the assistant role. Gesture/power-button behavior depends on the Pixel's Android settings. Real-device verification required. |
| Headset voice command | Android routes `ACTION_VOICE_COMMAND` | Handler registered; headset routing is controlled by Android and the accessory. No promise of universal button support. |

There is no custom wake word, always-on/background microphone, screen-reader access,
notification listener, floating overlay, Android Auto or Wear OS app in this change.
Pixel's reserved Now Playing / At a Glance surfaces are not generic app entry points.
No device-control tools are implemented: Nano cannot actually send messages, set alarms,
read other apps, browse, or change settings. The assistant prompt says so explicitly.

## Privacy and limits

- Model prompts stay with the on-device AICore API. There is no cloud inference fallback.
- Recognition uses `createOnDeviceSpeechRecognizer()` only; TTS refuses network and
  not-yet-installed voices. System-managed model/language downloads need connectivity.
- Successful conversation text and unsent drafts are saved in app-private preferences;
  audio is not saved. Backup remains disabled. The widget displays the last answer.
- New clears conversation, draft and widget result. AICore's internal caches are managed
  by the system; this is not a forensic-erasure claim.
- Speech language is explicitly selected or follows the system locale; the app does not
  detect the language of each utterance. A matching installed recognition/TTS pack is required.
- Nano is a small model with input/output limits and per-app quotas. History remains bounded;
  very long prompts may fail and are restored for editing. Generation allows 1,024 output tokens.
- Stop cancels the active future and invalidates callbacks. System model downloads may
  continue after cancellation; no subsequent generation from that request is accepted.
- The core variant remains a deterministic uppercase demo, not an AI assistant.

## Delivery finding

The workflow does **not** configure a persistent signing key. Its previous preview notes
incorrectly promised the same debug key across builds. Fresh CI runners may generate a
new key, which means a preview may not upgrade the installed APK. The misleading promise
has been removed. A proper stable signing secret is still needed for guaranteed upgrades;
no private signing key has been generated or committed by this change.

Do not uninstall an existing app just to try the preview without preserving anything you
want to keep: uninstalling deletes its local conversations. Install failures from different
keys must be resolved using the original signing key or a deliberate fresh installation.

## Device acceptance checklist

Use the **nano** APK from the branch preview, not the core APK. Validate on the actual
Pixel 10 Pro XL before merging this draft PR:

1. Prepare AICore online once. In airplane mode, type a question and a contextual follow-up.
2. Type a draft, dictate additional words, correct them, and send. Verify the reply speaks.
3. Read a typed answer aloud, stop it, then immediately ask another question. Old speech
   must not resume. Check speaker and Bluetooth/headphones with music/call interruptions.
4. Select English and Swedish in turn. Verify the matching installed voices; remove or
   select a missing language and verify an explicit message, with no cloud fallback.
5. Enter through tile, widget Talk and launcher Talk. Ask a follow-up to the typed chat,
   then choose Type and verify all successful turns are visible and used as context.
6. Enable Keep talking; verify a second utterance is accepted only after playback ends.
   Silence and screen backgrounding must stop the loop. Microphone indicator must clear.
7. Stop a generation, start New during a stream, rotate during a stream, deny microphone
   permission, and leave/reopen. No stale reply may reappear; an interrupted draft is recoverable.
8. Share text while chat is open; verify it is staged once and does not overwrite a draft.
   Use Ask Nano on read-only and editable selections; verify copy and replacement semantics.
9. Choose Pixel Surface Lab as default digital assistant; test the configured assist gesture,
   power button if supported, secure lock screen and accessory voice-command routing.
10. Check large font, landscape, keyboard visibility, two-row widget resizing, long answers,
    TalkBack content and 48dp primary controls. Check screenshot artifacts as well as the phone.

## Validation

The local source/resource verifier and its 11 fault-injection checks passed during authoring.
Robolectric regression tests cover conversation lifecycle, voice history, offline voice selection,
TTS failures/stream completion, continuous-mode termination, drafts, intents and control layout.
The PR's GitHub Actions run is the authoritative compile/test gate for both variants.
AICore and actual audio hardware cannot be exercised by Robolectric; passing CI is not a
claim that this checklist has been executed on a phone.

## References

- [ML Kit Prompt API setup and limits](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [GenerativeModelFutures reference](https://developers.google.com/android/reference/com/google/mlkit/genai/prompt/java/GenerativeModelFutures)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Assistant role qualification in Android's source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core-role/src/main/java/androidx/core/role/RoleManagerCompat.java)
- [Android RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)
