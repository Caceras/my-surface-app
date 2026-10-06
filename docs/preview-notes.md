# Current preview changes

**Keep the audio, get the speakers.** This build adds a *Record audio* mode that keeps the recording itself on the phone, and Gemini transcription with speaker labels (Swedish/English detected automatically) when you ask for it. Guide and limits: [second brain](https://github.com/Caceras/my-surface-app/blob/main/docs/second-brain.md).

- **Record audio** (Transcribe → *Record audio*): saves compact audio (about 14 MB per hour) in parts of up to 25 minutes, keeps going with the screen locked, and pauses/stops from the notification. A part cut off by a crash still plays and is picked up again the next time you open Transcribe or Notes.
- **Transcribe with Gemini** (on a recording note): uploads the audio with your Gemini key to `gemini-3.5-transcribe`, returns time-stamped lines labelled *Speaker 1*, *Speaker 2*… and fills the empty note (verbatim original included). Google is asked not to keep the request, and the upload is deleted straight afterwards. About US$0.005 per minute of audio; the estimate is shown before anything is sent. Keep the screen open while it runs; leaving cancels it.
- **Name speakers:** replace *Speaker 1* with real names in the working text; the verbatim original keeps the labels.
- **Play recording:** listen to all parts in order from the note.
- **Share audio into Ægentica:** share an M4A/AAC/MP3/OGG/Opus/WAV/FLAC/WebM file (for example a Pixel Recorder export or a voice message) to get a private recording note. Nothing is uploaded until you choose *Transcribe with Gemini*.
- **Earlier in this preview:** Gemini Live voice with read-only note tools, Insights, Show changes and the Transcribe tile (build 180); Transcribe with verbatim originals, Polish, recall and the Markdown vault (build 176).

Try on the Pixel: Transcribe → *Record audio* → *Start recording* → talk with someone for a minute → *Stop & save* → open the note → *Transcribe with Gemini* → *Name speakers*. Needs the Gemini preset in Connected AI. Signing continuity is still not provisioned: export your workspace before any reinstall.
