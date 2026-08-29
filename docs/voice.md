# Voice

Design, not yet built. Everything here is framework-only and both flavours get
it, which is the reason it looks the way it does.

## The rule that keeps it simple

**Modality is inherited, not configured.** How the request started decides how
the answer arrives:

| You started by | You get back |
|---|---|
| typing | text |
| speaking | text *and* speech |

That is the whole feature. There is no voice mode to enter and leave, no
toggle, no settings screen, no preference to persist — one boolean carried
alongside the request. "Voice mode" done properly is not a mode; it is an input
that remembers how it was asked.

Everything below follows from that.

## The seam

`SurfaceBrain` is an interface because it has two implementations. Voice has
one, so it gets no interface — two small classes in `src/main`, framework APIs
only, and the dependency-free invariant is untouched:

```
Ears   android.speech.SpeechRecognizer      words in
Mouth  android.speech.tts.TextToSpeech      words out
```

Neither knows about `Task`, `Prompts` or the brain. The activity owns the
sequence:

```
IDLE ──mic──► LISTENING ──silence──► THINKING ──first sentence──► SPEAKING ──► IDLE
                  │                      │                            │
             live transcript        streamed text                 barge-in
             + level meter          (already exists)             stops it dead
```

Five states, one enum. That is the entire state model.

**Voice is not a `Task`.** `Task` cases are text-selection menu entries —
`verify.py` check 8b enforces one `<activity-alias>` per case and one case per
alias, so a `Task.SPEAK` would either fail the checker or put a junk item in
the selection popup. Voice rides on `Task.ASK` and stays orthogonal.

Hands-free there is no selection, so the transcript arrives as the
`instruction` with `input` empty. That is right for `Prompts.user`, and wrong
for `CoreBrain`, which uppercases `input` and ignores `instruction` — core
voice would answer with nothing. Fix it in the brain rather than the activity:
`(input.ifBlank { instruction }).uppercase(…)` is one line, and it preserves
the rule that no surface ever branches on which brain it got.

## Where the mic goes

Three places, not five. A mic on every surface is the version of this that
feels cluttered rather than magic.

| Surface | Mic | Why |
|---|---|---|
| Launcher **Ask** box *(nano)* | yes | obvious, and the transcript stays editable so a misheard word is a fix, not a redo |
| **Text selection** Ask dialog *(nano)* | yes | the highest-value one: your hands already did the selecting, so asking out loud is genuinely faster |
| New `VoiceActivity` (translucent) | it *is* the mic | one class, reached from the tile, an app shortcut, and the widget |
| Presets (Summarise, Proofread, Rewrite) | no | a preset needs no words |
| Share sheet | no | you are already holding the phone and the text |

The first two rows are nano-only, and not by choice: `MainActivity` builds the
Ask box only when `brain.tasks` contains `Task.ASK`, and the `.Ask` alias lives
in the nano manifest, so in `core` neither surface exists to put a mic on.
Core's voice is the hands-free path — which is enough for what `core` is for,
proving the plumbing before a model is near it.

`VoiceActivity` is the hands-free path and the only new component: tap the
Quick Settings tile, talk, hear the answer. `minSdk` is 29, so the tile has to
branch: `startActivityAndCollapse(PendingIntent)` from API 34, and the `Intent`
overload — deprecated there, but the only one that exists below it — under
that. The `PendingIntent` needs `FLAG_IMMUTABLE`, same as the widget's: from
API 31 a `PendingIntent` built with neither mutability flag throws. See
[`surfaces.md`](surfaces.md).

The answer still goes through `ResultStore.save()`, so what you asked in the
kitchen is on the home screen widget afterwards. That falls out for free.

## The four details that make it feel like magic

Everything else is plumbing. These are the parts worth getting right.

**1. Speak the first sentence before the answer finishes.** The brain already
streams — `onPartial` fires with text as Nano produces it. Buffer to a sentence
boundary and `speak(chunk, QUEUE_ADD, …)`. Time to first spoken word drops from
"the whole answer" to "one clause". This is the single biggest perceived-latency
win in the design and it is about twenty lines:

```kotlin
/** Complete sentences in [text] beyond [from], and the new cursor. */
fun nextChunk(text: String, from: Int): Pair<String, Int> {
    val tail = text.substring(from)
    val end = tail.indexOfLast { it in ".!?\n" }
    if (end < 0) return "" to from
    return tail.take(end + 1).trim() to from + end + 1
}
```

Pure function, no Android, tested in `LogicTest`. `onResult` then flushes
whatever is left past the cursor, terminator or not — without that, "Sure" and
every answer whose last sentence lacks a full stop is printed and never spoken,
which breaks the inheritance rule in the case the user notices most.

**2. Never a silent listening screen.** `onPartialResults` puts words on screen
as they are recognised; `onRmsChanged` scales a single dot. Same principle the
process-text dialog already states: the stream *is* the progress indicator, so
there is never a blank spinner.

**3. Silence is the send button — hands-free only.** In `VoiceActivity` there is
no screen to look at, so `onResults` goes straight into `brain.run`: one tap
total, at the start. In the launcher and the selection dialog the transcript
lands in the box you were already looking at and Send stays where it is,
because the promise there is that a misheard word is a fix rather than a redo.
The surface decides, the same way it decides the modality.

**4. Barge-in.** Any touch, and the mic button itself, calls `tts.stop()`
immediately. `stop()` only clears what is already queued, though, and the brain
is very likely still streaming — so barge-in must also set a muted flag the
chunker checks, or the next sentence boundary starts it talking again half a
second later. Not being able to shut it up is what makes a voice assistant feel
like an appliance.

## Keeping the offline promise literally true

This is the one place where the easy path quietly breaks what the README
promises, so it is worth being exact.

- Use `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31), gated on
  `isOnDeviceRecognitionAvailable()` (API 31). **Never** `createSpeechRecognizer()`:
  its own class documentation says the implementation "is likely to stream audio
  to remote servers".
- `RecognizerIntent.EXTRA_PREFER_OFFLINE` is a *hint* the service may ignore. A
  hint is not a promise, and this app's promise is absolute.
- Text-to-speech the same way: pick a `Voice` whose `isNetworkConnectionRequired`
  is false **and** whose `features` do not contain
  `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED` — that key means "local, but
  the data still has to be downloaded", which with the radios off is a voice
  that cannot speak. If nothing qualifies, print the answer and stay quiet
  rather than half-succeeding.
- **No fallback to cloud recognition, ever.** Two failures live here and they
  are easy to conflate:
  - *No on-device recognizer at all.* `createOnDeviceSpeechRecognizer()` throws
    `UnsupportedOperationException` when `isOnDeviceRecognitionAvailable()` is
    false, so there is no instance to call a download on. No mic button, honest
    status line, stop.
  - *Recognizer present, language pack missing.* This is the Swedish case, and
    the one worth handling. `checkRecognitionSupport()` (API 33) hands back a
    `RecognitionSupport`, whose `getSupportedOnDeviceLanguages()` means
    "supported, needs downloading" — that, and not the case above, is what
    `triggerModelDownload()` is for. It is a looser mirror of Nano's
    `download()` than it first looks: the API 33 overload takes an intent and
    nothing else, and the reference says to verify the outcome by calling
    `checkRecognitionSupport()` again — the progress-and-completion listener
    is API 34. So on 33, trigger, then re-check before believing speech is
    ready; call `startListening()` straight after the trigger and it still
    fails with `ERROR_LANGUAGE_UNAVAILABLE`. Both methods are API 33, so on 31
    and 32 there is no support query and no download to offer: `ERROR_LANGUAGE_UNAVAILABLE` exists from 31, so
    the honest move there is to report it and send the user to the system's
    own voice-input language settings — not to guess, and still not to fall
    back to the cloud.

  Voice status belongs on the same launcher status line and the same tile
  subtitle as the model status.
- **Say which locale is being asked for.** There is no settings screen, so the
  recognizer gets `EXTRA_LANGUAGE` from the device's current input locale,
  alongside `EXTRA_LANGUAGE_MODEL` (`LANGUAGE_MODEL_FREE_FORM` — the reference
  marks this extra *required*) and `EXTRA_PARTIAL_RESULTS`. Build that intent
  once and reuse the same instance for the support check, the download and
  `startListening`, because that is the request they each answer for — check for
  one language and download another and the Swedish speaker still gets
  `ERROR_LANGUAGE_UNAVAILABLE`. Framework language *detection*
  (`EXTRA_ENABLE_LANGUAGE_DETECTION`, `DETECTED_LANGUAGE`) is API 34+, so it
  cannot be the mechanism here. The requested locale is the answer instead: it
  is a real BCP-47 tag, it is what the recognizer was asked for, and it is
  what the TTS voice should be chosen from. `Lang` is the fallback for when
  there is no requested or detected locale to use — it only tells Nordic from
  not-Nordic, so leaning on it would read Spanish and French back in the
  device default.
- Below API 31 there is no on-device recognizer API at all, so there is no mic
  button. `minSdk` stays at 29. Hiding a button covers the launcher and the
  dialog but not the entry points that live outside the app. A static shortcut
  is declared in the manifest and is on the launcher from the moment the app
  installs, before a line of app code runs, so disabling it through
  `ShortcutManager` is too late — the voice entry belongs in an
  `xml-v31/shortcuts.xml` that older devices never load, or nowhere. The tile
  reports it in its subtitle like any other unavailable state, and
  `VoiceActivity` opens on that status rather than a mic it cannot use. A
  shortcut promising "tap, talk" on a Pixel 4 is worse than no shortcut.
- Nothing is recorded to disk. "No audio is ever written" is a stronger claim
  than a privacy policy and it costs nothing to keep.

## The permission

`RECORD_AUDIO` is the first runtime permission this app has ever needed, and it
is the real cost of the feature. Ask for it on the first mic tap, never at
launch, and use the rationale line to say the true thing: *audio is transcribed
on this phone.*

There is a way to avoid the permission entirely: fire
`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with `startActivityForResult` and let
the system's own recognizer app hold the mic. It is the cleanest option on
paper and it is the wrong one here — you get a system dialog instead of your UI,
no partial results, no level meter, no barge-in, and offline is back to being a
hint. It is the least code and the least magic, and it breaks the one promise
the app is built on.

The Quick Settings tile cannot request a permission. It launches `VoiceActivity`,
which can.

## Manifest, before it costs you a CI round trip

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- targetSdk 36, so package visibility applies. Without these the
     recognizer and the TTS engine fail to bind, silently. -->
<queries>
    <intent><action android:name="android.speech.RecognitionService" /></intent>
    <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
</queries>
```

`VoiceActivity` needs `android:exported` on its filter — `verify.py` check 7
catches that one for free.

## Traps

1. **Every `SpeechRecognizer` method must be called from the main thread**, and
   `destroy()` is mandatory. Skip it and the mic stays held after the activity
   is gone, which breaks recognition in *other* apps until the process dies.
   `Mouth` needs the same discipline: `tts.stop()` is barge-in, not teardown —
   `onDestroy` has to call `shutdown()`, or every trip through the tile leaves
   another engine connection bound.
2. **Recognizer callbacks arrive on the main thread; TTS
   `UtteranceProgressListener` callbacks do not.** Post back, exactly as the
   nano brain does with `DownloadCallback`.
3. **`onEndOfSpeech` is not `onResults`.** Send on `onResults`. Use
   `onEndOfSpeech` only to flip the UI to "thinking", or you will send an empty
   string.
4. **`ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` are the normal "you said
   nothing" path**, not failures. Return to idle and show nothing. Treating them
   as errors produces a toast storm.
5. **TTS init is asynchronous.** `speak()` before `onInit` is silently dropped,
   and by then the brain may already have streamed several sentences. Hold the
   pending chunks in a queue and flush them in order on init — one slot that
   each new chunk overwrites starts the answer from the middle.
6. **Set the TTS locale from what was recognised, not the device default**, or
   Swedish gets read back in an English accent. `Lang.looksNordic` already
   exists — reuse it rather than adding a second language guess.
7. **`SurfaceBrain.run` cannot be cancelled.** It returns nothing and takes no
   token: the callbacks arrive later on the main thread whether or not anyone
   is still listening. Leave `VoiceActivity` mid-answer and a chunk lands on a
   dead view, `speak()` is called on an engine already shut down, and a result
   the user walked away from is saved to the widget. Teardown must set a
   cancelled flag that `onPartial` and `onResult` check first. Putting real
   cancellation in the interface would be the better fix and a wider change
   than voice should make on its own.

## This part is testable, unlike the AI path

The nano brain needs AICore, which exists on no CI runner. Voice does not have
that problem: Robolectric 4.16 ships `ShadowSpeechRecognizer`
(`setIsOnDeviceRecognitionAvailable`, `triggerOnPartialResults`,
`triggerOnResults`, `triggerOnRmsChanged`, `isDestroyed`,
`getLastRecognizerIntent`) and `ShadowTextToSpeech` (`getSpokenTextList`,
`getQueueMode`, `isShutdown`). The whole path runs on the JVM against `core`,
whose brain is deterministic — speak, and it uppercases and reads it back.

Worth a test each:

- the recognizer asked for is the on-device one, with partial results enabled
- partials reach the screen while listening
- `onResults` runs the brain exactly once
- the first sentence is spoken before the answer completes
- a final sentence with no full stop is still spoken when the answer completes
- barge-in stays silent while the answer is still streaming
- the recognizer is destroyed and the TTS engine shut down when the activity
  finishes (the mic-leak and engine-leak regressions)
- `ERROR_NO_MATCH` returns to idle without a dialog
- a brain callback arriving after the activity is destroyed changes nothing

Two checks worth adding to `verify.py`, both catching silent runtime failures,
which is what that script is for: Kotlin referencing `SpeechRecognizer` implies
`RECORD_AUDIO` in the manifest, and referencing either speech class implies the
matching `<queries>` entry.

## Not building

- **A wake word.** Always-on mic, foreground service, battery, and a policy
  problem. Not for a prototype you sideload.
- **Continuous conversation.** Turn-taking and echo cancellation turn this from
  an afternoon into a product.
- **A voice settings screen.** The inheritance rule at the top removes the need.
- **Saving audio.** See above.

## The dependency we are not taking, yet

ML Kit now ships `com.google.mlkit:genai-speech-recognition` (1.0.0-alpha1) —
same AICore family, streaming, with an "advanced" mode that transcribes through
the on-device Gemini model on Pixel 10 and 11. Two reasons it stays in this
document and out of the build:

- It would be a **second** dependency in `nano`, at `alpha1` against
  `genai-prompt`'s `beta4`, sharing `genai-common`. That is precisely the
  version-resolution trap [`ai.md`](ai.md) already warns about.
- Its language coverage is English plus a handful of European betas. The
  framework on-device recognizer takes downloadable language packs, Swedish
  included. For a repo that treats language honesty as a feature, the free
  option is also the better transcriber.

Same shape as the MediaPipe / LiteRT-LM note in [`ai.md`](ai.md): a real upgrade
path, written down, not taken today.

## Cost

Two new files (`Voice.kt`, `VoiceActivity.kt`, ~250 lines), small edits to
`MainActivity`, `ProcessTextActivity`, the tile, the manifest and `strings.xml`,
six tests and two checker rules. One afternoon, and `core` gets voice too.
