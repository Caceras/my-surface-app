# Current preview changes

**Transcription-first second brain.** Capture spoken words verbatim, let AI polish a copy, and find your own words again. Details and limits: [second-brain guide](second-brain.md).

- **Transcribe** (Today, Notes, or long-press the launcher icon): long-form on-device transcription with time-stamped lines saved as you speak. Keeps going with the screen locked; the notification has Pause and Stop & save. It never starts or restarts by itself, and pauses if you dictate elsewhere.
- **Swedish on-device speech:** the Nano build tries Google's new on-device Advanced recogniser (lists `sv-SE` on Pixel 10) and falls back to Android's recogniser, showing which engine is active. *Use Android's recognizer only* switches it off.
- **Verbatim stays verbatim:** transcripts keep a protected original. **Polish** cleans a copy for review (Replace text or Save as new note); **Verbatim → Use as text** restores it. *Polish dictation* is also in the text-selection menu.
- **Recall:** questions now search your notes and transcripts and cite matching excerpts ("From your notes"). On-device by default; connected AI needs its own opt-in.
- **Gemini:** Connected AI has a *Use Google Gemini* preset (`gemini-3.8-flash`, your own AI Studio key).
- **Markdown vault:** Settings → Markdown vault writes Obsidian-compatible files (properties, `[[links]]`, verbatim section) to a folder you choose and refreshes when you leave the app.
- **Fixes:** launcher *Capture note* and *History* shortcuts open their destinations again; Tasks → Add task opens a new task editor.

Try on the Pixel: a 10-minute Swedish transcript with the screen locked, Stop & save, Polish, then ask "what did I say about …?". Report the engine shown on the Transcribe screen. Raw audio is not stored yet; export your workspace before any reinstall because signing continuity is not provisioned.

Earlier preview notes are kept in each build's GitHub release description and in git history.
