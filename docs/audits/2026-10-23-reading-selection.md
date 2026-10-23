# Reading voices, read-aloud pauses and the selection sheet (23 October 2026)

Owner report from the Pixel (build 195), with screenshots of the reader, HayaiTTS settings and Google's selection translate sheet.

| Finding | Evidence | Fix |
|---|---|---|
| Installed HayaiTTS voices never appeared ("No installed offline voice for English"). | `SpeechVoices.ranked` compared `voice.locale.language == "en"`. Offline engines built on older APIs report three-letter codes ("eng") or list no voices. | Languages are compared through ISO 639-2 codes, malformed tags ("en_US") are normalised, and an engine that lists no voices but supports the language uses its own voice, unless it reports that voice as missing or online. |
| Build 195 on the owner's Pixel, after the report: the picker still said "No installed offline voice for English", and the microphone said "Offline speech for English (Sweden) is not ready yet." | Everything used Android's default engine only, so one voice app that failed to start or offered no voice ended reading. The phone language is English (Sweden), and the on-device recogniser has no pack with that name. | `VoiceOutput` tries the chosen engine (else the default), then Google's engine, then the other installed engines, each once; the picker lists voice apps and says which one has no voice. After a missing-language error, the microphone switches once to an installed pack of the same language (en-SE to en-US) and listens again. Regressions: `ReaderRepairTest` (fallback to Google's engine, engine order) and `VoiceTest` (en-SE switches to en-US). |
| Read aloud spoke one sentence, then paused before the next. | Android's speak queue lets an engine run only about 500 ms ahead of playback, so a slow on-device engine starts each sentence after the previous ends. | The reader renders the next four sentences to audio files (`synthesizeToFile`) and plays them itself; rendering overlaps playback. Engines that cannot render files fall back to the old queue. A paused sentence resumes mid-sentence. |
| The owner wanted Ægentica where Google shows its translate sheet on selected text. | Screenshots of Google's selection sheet over Chrome. | The selection menu's *Ægentica* opens the frosted assistant sheet with the selection as context (marked as data) and selection chips. *Replace selection* returns the answer to editable fields through `EXTRA_PROCESS_TEXT`. *Listen* reads a selection aloud. A selection never clears a shared assistant screen. |

Gboard has no extension API, so the keyboard idea is a separate input method, **Ægentica keys** (`AegenticaKeyboard`, panel in `KeyboardPanel`), that the owner switches to from Gboard and back. Design choices:
- **Reading and writing.** It reads the field (or its selection) only when an action is tapped, never in password fields, and writes only on *Replace* or *Insert*.
- **Prompting.** Field text is sent as marked data with a dedicated `Task.KEYBOARD` prompt that returns only the edited text.
- **Replies.** *Reply to copied* answers the clipboard's message; it is inserted at the cursor, never replacing the field.
- **Dictation.** It uses the same on-device recogniser as the rest of the app.
- **Model.** It uses the assistant's model choice, so the connected model needs its existing opt-in. Gemini Nano may refuse while another app is in front; that is a device check.

Device checks still needed: HayaiTTS voice list and preview, gap-free reading with a large voice, selection *Ægentica* in Chrome on read-only and editable text, *Replace selection*, *Listen*, and Ægentica keys in Messages and Gmail (Nano and connected model, dictation, switching back).
