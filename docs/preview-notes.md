# Current preview changes

**New in this build:**
- **Ægentica opens into Glimt.** The app's home is now Glimt, the chat bar from AwesomeSEO: the same design, models, memory and page reading as Glimt Web, with what only the phone can add.
  - **Read aloud** uses your phone's voices (HayaiTTS and your other voice apps), without pauses between sentences.
  - **The microphone** uses the phone's own offline dictation: words appear as you speak, free.
  - **Sign in to OpenRouter** opens Chrome, where your usual sign-in works (Google included), and comes back by itself.
  - **Share to Ægentica** from any app: a link opens as a page Glimt reads; text becomes a page to ask about.
  - **The mark** beside the address opens notes and tasks, recording and transcription, hands-free voice, the reading voice and the phone settings.
  - Glimt's design comes from its server, so improvements arrive without reinstalling the app.

**Earlier in build 206:**
- **A new look, matching aegentica.se.** White and black instead of sky blue, a clean header with icons, "What should we work on?" on an empty chat, and one rounded message box: + for Actions, a bookmark to save a note, the microphone, and a round button that starts Voice when the box is empty and sends when you have typed. Answers get a small row of icons, and the bottom tabs have icons.
- **Your open-source voices come first.** Read aloud now uses the voice app you choose in Ægentica, then the open-source voice apps you installed, such as HayaiTTS, even when Android's default is Google's. Google's voice is used only if you pick it, or if it is the only voice app on the phone. Settings → *Choose reading voice* lists your voice apps and each voice by name (for example Kokoro or Piper speakers), with a sample.
- **The microphone works with English (Sweden).** Android has no offline speech pack called English (Sweden). Ægentica now switches to an installed English pack, such as English (US), and listens again.

**Earlier in build 198:**
- **Your voices work.** Offline voice apps that name languages with three-letter codes, such as HayaiTTS, now show their installed voices; an app that lists none uses its own voice.
- **Read aloud without pauses.** The next sentences are prepared while one plays, so slow offline voices no longer stop after every sentence.
- **Ægentica keys.** An AI panel beside Gboard: in any text field, switch to it with the keyboard button, then Fix, Improve, Shorter, Friendlier, Formal, Translate or Continue what you wrote, *Reply to copied* for a message you copied, or dictate. Nothing is written until you tap *Replace* or *Insert*, and password fields are never read. Turn it on in Settings → *Ægentica keys*.
- **Ægentica in the selection menu.** Select text in any app and choose *Ægentica*: the frosted sheet opens over the app with Translate, Explain, Summarize, Draft a reply and Improve. In a text field, *Replace selection* puts the answer back. *Listen* reads the selection aloud.

**Your phone's assistant, any model, real-time voice.** Ægentica can now be your *Digital assistant app*: it reads the screen you are on when you ask, and answers with Gemini Nano on the phone, any OpenRouter model, or a model on your own Mac. Agent voice adds a real-time LiveKit agent that can see a screen you share. Guides: [second brain](https://github.com/Caceras/my-surface-app/blob/main/docs/second-brain.md) · [agent voice](https://github.com/Caceras/my-surface-app/blob/main/docs/agent.md).

- **Phone assistant:** Settings → *Phone assistant* explains the two steps. Choose Ægentica under Default apps → *Digital assistant app* (this replaces Gemini there). Then swipe up from a bottom corner, or hold the power button, in any app. A frosted sheet opens at the bottom of the app: ask about the screen (text and screenshot), by typing or speaking. Icons under the answer copy, save, share, read aloud or *Talk live*. Tap outside the sheet to close it. Links and phone numbers in the answer become buttons. Nothing is ever tapped or typed in the other app.
- **OpenRouter and local models:** Connected AI has three presets.
  - *Gemini* keeps its own key, so Live voice and Transcribe still work when chat uses another provider.
  - *OpenRouter* gives one key for hundreds of models, with a searchable model list showing what each can see or hear and its price. Providers that store prompts are skipped.
  - *Local* reaches Ollama or LM Studio on your Mac through Tailscale.
  - Answers now stream. Turn on *Also use it for voice and the screen assistant* to talk to your chosen model.
- **Agent voice (AI page):** real-time voice with your own LiveKit agent. It runs any OpenRouter model, or Gemini Live, and searches your notes. It can see a screen you share while you use other apps, and suggests timers, alarms, events, places, calls, searches and notes that run only when you tap them. It runs on your own server next to your LiveKit; [setup](https://github.com/Caceras/my-surface-app/blob/main/docs/agent.md).
- **Talk about this screen:** from the assistant, *Talk live* starts Gemini Live with the screenshot and screen text already shared.
- **Earlier in this preview:** Record audio and speaker-labelled Gemini Transcribe (build 183); Live voice, Insights and the Transcribe tile (build 180); Transcribe, Polish, recall and the Markdown vault (build 176).

Try on the Pixel: Settings → *Phone assistant* → *Open settings* → choose Ægentica AI. Open any app, swipe up from a bottom corner, and tap *Explain this*. Then Connected AI → *OpenRouter* → paste a key with a credit limit → turn on voice and the assistant, and ask again. Signing continuity is still not provisioned: export your workspace before any reinstall.
