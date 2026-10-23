# Reading voices, read-aloud pauses and the selection sheet (23 October 2026)

Owner report from the Pixel (build 195), with screenshots of the reader, HayaiTTS settings and Google's selection translate sheet.

| Finding | Evidence | Fix |
|---|---|---|
| Installed HayaiTTS voices never appeared ("No installed offline voice for English"). | `SpeechVoices.ranked` compared `voice.locale.language == "en"`. Offline engines built on older APIs report three-letter codes ("eng") or list no voices. | Languages are compared through ISO 639-2 codes, malformed tags ("en_US") are normalised, and an engine that lists no voices but supports the language uses its own voice, unless it reports that voice as missing or online. |
| Read aloud spoke one sentence, then paused before the next. | Android's speak queue lets an engine run only about 500 ms ahead of playback, so a slow on-device engine starts each sentence after the previous ends. | The reader renders the next four sentences to audio files (`synthesizeToFile`) and plays them itself; rendering overlaps playback. Engines that cannot render files fall back to the old queue. A paused sentence resumes mid-sentence. |
| The owner wanted Ægentica where Google shows its translate sheet on selected text. | Screenshots of Google's selection sheet over Chrome. | The selection menu's *Ægentica* opens the frosted assistant sheet with the selection as context (marked as data) and selection chips. *Replace selection* returns the answer to editable fields through `EXTRA_PROCESS_TEXT`. *Listen* reads a selection aloud. A selection never clears a shared assistant screen. |

Not changeable from the app: Gboard has no extension API. The keyboard idea is a separate input method that opens next to Gboard; see the follow-up work.

Device checks still needed: HayaiTTS voice list and preview, gap-free reading with a large voice, selection *Ægentica* in Chrome on read-only and editable text, *Replace selection*, *Listen*.
