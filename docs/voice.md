# Voice

> **Everyday workspace update:** [Current behavior and limits](everyday-workspace.md) covers Today · AI · Library, SQLite migration, notes/relations/tables, explicit background reading, calendar/Beeper access and optional connected AI/routines. Earlier foreground-only and preferences-only descriptions below apply to the original chat/Voice path unless updated here.

[Setup and controls](getting-started.md#voice-setup) · [Architecture](architecture.md#voice-lifecycle) · [Device checks](testing.md#physical-pixel-checklist)

## Two intentional modes

**Dictation** in chat or a selection prompt leaves words editable. The user presses Send after review. **Voice** is a foreground conversation: Android finalizes an utterance after a pause and the app sends it. Finish speaking can ask Android to finalize earlier. Voice replies are spoken and shown as text.

Typed replies are quiet by default. Listen reads a completed reply; Settings can enable Read every reply aloud. Keep talking opts into listening again after a voice reply, only for the current foreground session.

## Input

`Ears` in `Voice.kt` uses the API 31+ on-device recognizer and checks its availability. It never falls back to a general recognizer. Recognition clients are destroyed on final result, error, cancellation and activity exit. Session IDs reject late callbacks.

Support queries and downloads use the chosen speech locale. Selection prefers an exact supported tag, then the same language; it does not silently switch to an unrelated language. API 33 supports model download requests; API 34 adds progress/outcome callbacks. Queued downloads are reported as queued. A timeout and system-settings route prevent setup from becoming an indefinite spinner. Initial downloads need connectivity.

Setup does not start capture, including when a setup intent reopens an existing Voice activity. Actual Talk entry asks for microphone permission when necessary. Silence/timeout ends a continuous session without an automatic retry loop.

## Output

`Mouth` chooses an installed matching-language TTS voice that neither requires a network nor declares missing data. It queues sentences while the engine initializes, splits long utterances below the engine's limit, and uses generation-specific utterance IDs. If initialization or voice selection fails, the answer remains text and a recovery message is shown.

Speech follows complete sentences as they arrive; the final response flushes an unfinished tail. UI partial rendering is coalesced separately, so visual batching does not delay speech submission. This does not guarantee a particular first-audio latency on a device.

Quiet voice mutes future chunks while text generation continues. Stop response cancels generation. Stop speaking ends playback after the answer is complete. Scrolling the Voice screen does not itself mute speech. Audio-focus loss pauses playback with an explanation and prevents the interrupted answer from speaking again automatically.

## Native audio controls

A framework MediaSession is active while foreground speech is queued/playing. Pause and Stop requests use the same muting/failure path as interruption, so future streamed chunks remain quiet. There is deliberately no automatic Play/resume command. The session exposes only generic branding, never the answer text. ACTION_AUDIO_BECOMING_NOISY stops speech on headphone disconnect. Session and receiver resources are released when the speaker closes.

This does not add background playback, lock-screen recording or a notification player. Device-level media-button routing and Bluetooth interruption must be tested on the Pixel.

## Boundaries

No wake word, background microphone service, background inference, guaranteed Bluetooth routing, or universal speech-language support is implemented. A working language pack and a working TTS voice are separate from Gemini Nano's language quality. Device vendors/services may differ in permission, language download, and audio behavior.

## API references

- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport)
- [ModelDownloadListener](https://developer.android.com/reference/android/speech/ModelDownloadListener)
- [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Audio focus](https://developer.android.com/media/optimize/audio-focus)

These links describe platform contracts. The JVM tests cover the app's handling of those contracts; real recognition and playback need the physical-device checklist.

Opening chat navigation sheets stops capture/playback just as leaving the chat does. Quiet voice remains quiet through the final result; the UI does not restore an active mute control for an already muted response. Real headset/engine validation remains in the device checklist.

## Recreation and failure

Rotating/recreating Voice pauses capture and preserves displayed text; tap Talk to continue. A failed or echoed response turns off Keep talking and saves the question to an empty typed draft. An existing different draft is preserved. Main and selection dictation level feedback follows Android’s disabled-animation preference. Selection prompts retain text/cursor during recreation and need an explicit Send after reviewing dictation.

## Explicit reader

`ReadingService` owns completed-text playback separately from the live `Mouth` stream. Listen on a note/answer opens `ReadingActivity`: sentence highlighting, pause/resume, previous/next and speed. A mediaPlayback foreground service, generic MediaSession metadata and native notification allow screen-lock/headset control. Position persists at sentence boundaries; after process death playback only resumes by an explicit user action. TTS voices still must be installed/offline. Audio-focus loss, headphone disconnect and all microphone entry points pause it. Navigation does not stop this explicitly requested reading session.

Notes use editable on-device dictation. Manual editing cancels recognition so late partials cannot overwrite a newer draft. Saved text is independent of model or recognizer availability. The alpha GenAI recognizer remains an evaluation opportunity, not an untested default replacement.
