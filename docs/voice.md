# Voice

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
