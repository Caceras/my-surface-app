# Second brain: transcription first

October 2026 · Ægentica AI · [Full research report](research/2026-10-second-brain.md) · [Everyday guide](everyday-workspace.md) · [Privacy](privacy.md)

Ægentica AI is a native Kotlin Android app (no WebView, no cross-platform layer). This iteration turns it toward a **transcription-first second brain**: capture spoken words verbatim, let AI polish a copy, find your own words again when you ask, and keep everything portable as plain Markdown.

## What this build does

| Capability | How it works | Boundary |
|---|---|---|
| **Transcribe** | One tap (Today, Notes, launcher shortcut or the *Transcribe* Quick Settings tile) starts long-form on-device transcription. Each recognised segment is saved immediately as a time-stamped line (`[12:04] …`). An explicit microphone foreground service keeps going with the screen locked; the notification offers Pause and Stop & save. | Never starts by itself, never restarts after process death, and pauses when you dictate elsewhere in the app. Stores recognised text, not raw audio. |
| **Verbatim original** | Transcript lines are appended to a protected original and to the working text in one transaction. Editing, polishing or replacing the text never changes the original. **Verbatim** shows it; **Use as text** copies it back. | A transcript continues in a linked "part 2" after about 190,000 characters instead of truncating. |
| **Polish** | AI cleans punctuation, filler and false starts on a copy, chunk by chunk, then shows the result. **Replace text** or **Save as new note** (linked to the source). Parts that come back much shorter are flagged. Also in the text-selection menu as *Polish dictation*. | Runs in the foreground (Nano) or with your connected provider. Nothing changes until you choose. |
| **Recall** | Every question searches your own notes and transcripts for its distinctive words and gives the best matching excerpts to the AI as numbered sources. The answer card lists *From your notes*. | Word search (full text), not semantic search yet. On by default on-device; a connected provider gets excerpts only after its own opt-in. Switch: **Settings → Recall matching notes when I ask**. |
| **Gemini (cloud)** | **Settings → Connected AI → Use Google Gemini** fills Google's OpenAI-compatible endpoint and the stable `gemini-3.8-flash` model. Paste a key from Google AI Studio. | Your key, your billing. Text chat and Polish; not Live voice or Gemini Transcribe yet. |
| **Live voice** | **AI → Talk live with Gemini** streams your voice to Gemini Live (`gemini-3.8-live`) and plays its spoken reply; interrupt any time. Gemini can call three read-only tools — *search_notes*, *read_note*, *list_tasks* — and the screen lists the notes it used. Google's periodic connection resets are resumed automatically. | Foreground only: leaving the screen ends it. The verbatim exchange (your words and Gemini's) is saved as a note. Requires the Gemini preset and your key; Nano build only. |
| **Insights** | **Insights** on a note proposes a title, summary, tasks, people and projects (Nano or connected AI). You tick what to add; items are created as linked records whose source names the provider and the note. **Undo** reverses the batch. | Never edits the note's text or original; names are matched to existing people/projects before creating new ones. Titles are only replaced when blank or automatic. |
| **Show changes** | **Verbatim → Show changes** compares the verbatim words with the current text word by word. | Up to 2,000 words per side on the phone. |
| **Markdown vault** | **Settings → Markdown vault** writes every record to a folder you pick as Markdown with YAML properties, `[[wiki links]]` for relations and a *Verbatim original* section. Unchanged files are skipped; it refreshes when you leave the app. | One-way export. Only files the app wrote (inside an `Aegentica` folder) are rewritten or removed. |

### Speech engines

On the Nano build, Transcribe prefers Google's on-device **GenAI Speech Recognition (Advanced mode)**, the only first-party on-device engine that documents Swedish (`sv-SE`) on Pixel 10/11. It is an alpha SDK: the first session may download the model and use Android's recogniser meanwhile, and Google's GenAI models may refuse to run while the app is off screen. In that case transcription continues with **Android's on-device recogniser**, if your language pack supports it, and the screen says so. Transcribe keeps the screen on while it is visible. **Use Android's recognizer only** forces the established engine. The core demo build always uses Android's recogniser.

## Set it up on the Pixel

1. Install the build-specific Nano APK from the release notes; check the build number in Settings.
2. **Settings → Set up voice & test playback** → choose **Svenska (Sverige)** or English and download offline speech.
3. Today → **Transcribe**. Allow the microphone and, on Android 13+, notifications (the running notification is your stop button).
4. Speak normally. Lock the phone if you like. **Stop & save** opens the transcript.
5. In the transcript: **Polish** → review → **Replace text** or **Save as new note**. **Verbatim** shows what was recognised.
6. Optional cloud: create a key at Google AI Studio, set a spending cap there, then **Settings → Connected AI → Use Google Gemini**, paste the key and enable. Leave *Include matching Library excerpts* off unless you want note excerpts sent to Google.
7. Optional vault: **Settings → Markdown vault** → choose a folder such as `Documents/Obsidian`. Open that folder as a vault in Obsidian for graph and table views.

### Moving the vault off the phone

| Option | Cost | Notes |
|---|---|---|
| Syncthing-Fork (Android) + desktop Syncthing | Free | The official Syncthing Android app was archived in 2024; the Fork is maintained. |
| Obsidian Sync | ~$4/month yearly | End-to-end encrypted; simplest across devices. |
| Obsidian Git / a private GitHub repo | Free | Version history; GitHub's MCP server lets desktop AI tools read the vault. |
| Google Drive | Free tier | Android's folder picker usually cannot choose a Drive folder. Use a desktop Drive client on a synced copy, or a future `drive.file` integration. NotebookLM reads Drive sources. |

## Verified versus still to check on the device

Automated JVM tests cover segment saving, continuous restart, silence handling, busy-microphone pause without retry loops, yielding to dictation, stop-time flushing of the last words, part rollover, polish review that leaves the original untouched, recall scoping and opt-ins, Markdown rendering, Gemini request shape and shortcut routing. They run against Android's framework through Robolectric, **not** against a Pixel.

Live voice and Insights are tested against a fake WebSocket, fake audio and a stub model (`LiveAndInsightsTest`): setup and tool declarations, audio only after setup, read-only tool answers, barge-in, transcript accumulation, resumption with the latest handle, stale-socket fencing, ending on leaving the screen, review/apply/undo and provenance.

Still requires the physical Pixel: a real Gemini Live session (key, latency, Swedish, echo on the loudspeaker vs headphones, interruption), Insights quality with Nano in Swedish, Swedish quality of both engines, whether Google's Advanced engine downloads/runs and how it behaves off screen, background capture with the screen locked for an hour, battery/heat, start-sound behaviour between sessions, polish quality with Nano in Swedish, Gemini key/billing, and folder providers for the vault (local folder, Obsidian vault, Drive).

## What is realistic next

The research ranks these by value and risk. Each phase is useful on its own.

1. **Bake-off (no code):** compare Android vs Advanced engine and Gemini Transcribe on three real Swedish recordings; compare Nano vs Gemini polish on 20 notes.
2. **Raw audio as the true original:** record compressed audio chunks next to the transcript so a better model can re-transcribe later; add a verbatim/polished diff view.
3. **AI layers with provenance (first version shipped as Insights):** reviewed titles, summaries, tasks, people and projects with source provenance and undo. Next: store the model and prompt version per derivation, and source spans (which sentence) for each item.
4. **Cloud transcription with speaker labels:** `gemini-3.5-transcribe` (Swedish, up to 8 speakers) on chunks of 30 minutes or less, about $0.30 per audio hour.
5. **Voice window (shipped in a first version):** Gemini Live with read-only note tools and session resumption. Next: optional Google Search grounding (requires showing search suggestions), and append-only "save this as a note" with explicit confirmation.
6. **Mirrors:** batched commits to a private GitHub repo; optional Drive folder for NotebookLM.
7. **Context from the phone:** default-assistant role for screen context on demand (replaces Gemini on the power button), notification access after *Allow restricted settings*.

Not possible for a third-party app today: reading Pixel Recorder, Call Notes or Journal data; recording phone-call audio; Gemini Personal Intelligence context (not offered in the EEA); being invoked by the Gemini overlay (AppFunctions is a private preview); running Gemini Nano in the background.

## Native toolkit decision

Google now calls Android "Compose-first" and has put the classic View toolkit this app uses into maintenance mode. Views remain fully native and supported, Material 3 Expressive is still alpha in Compose, and the value of this app is capture and data rather than widgets. Decision for now: keep framework Views, raise the target to Android 17 (API 37) in a dedicated upgrade, and revisit a Compose migration when Material3 1.5 is stable. A rewrite would be an owner decision, not an incidental refactor.

## Recording and privacy rules of thumb

Not legal advice. In Sweden, recording a conversation you take part in is not *olovlig avlyssning* (Brottsbalken 4 kap. 9 a §); spreading a recording can still be unlawful. Purely personal notes fall under GDPR's household exemption; anything for the agency or clients does not, and needs a legal basis, information to participants, a processor agreement for any cloud AI and deletion routines. Keep work capture separate from personal capture, never leave Transcribe running in a room you have left, and do not build voiceprint identification or emotion detection for work use.
