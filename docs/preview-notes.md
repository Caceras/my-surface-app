# Current preview changes

**Talk to your second brain.** This build adds real-time voice with Gemini, AI-suggested people, projects and tasks you approve, and a word-by-word view of what AI changed. Guide and limits: [second brain](https://github.com/Caceras/my-surface-app/blob/main/docs/second-brain.md).

- **Talk live with Gemini** (AI page): real-time voice with Gemini Live (`gemini-3.8-live`). Interrupt any time; Gemini can *search and read your notes* (read-only) and tells you which note it used. Leaving the screen ends the conversation, and the verbatim exchange is saved as a note. Needs the Gemini preset in Connected AI (your own AI Studio key; Google bills about US$0.005/min you speak and US$0.018/min Gemini speaks).
- **Insights** (any note or transcript): AI proposes a title, a short summary, tasks, people and projects. Tick what you want; items are created and linked back to the note with their source recorded, and **Undo** reverses the whole batch. The note's text and verbatim original never change.
- **Show changes** (Verbatim): word-level comparison of the verbatim transcript and the current text — removed words struck through, added words highlighted.
- **Transcribe tile:** add *Transcribe* in Quick Settings for one tap from anywhere; it shows Recording/Paused.
- **Earlier in this preview (build 176):** Transcribe with verbatim originals, Swedish on-device speech, Polish, recall from notes, Gemini preset and the Markdown vault.

Try on the Pixel: Connected AI → Use Google Gemini → paste key → AI page → *Talk live with Gemini* → ask "vad sa jag om …?" about a transcript. Then open that transcript → **Insights**. Use headphones if Gemini interrupts itself on the loudspeaker (or turn on *Pause my mic while Gemini speaks*). Signing continuity is still not provisioned: export your workspace before any reinstall.
