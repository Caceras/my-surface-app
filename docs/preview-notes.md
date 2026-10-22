# Current preview changes

**Your phone's assistant, any model, real-time voice.** Ægentica can now be your *Digital assistant app*: it reads the screen you are on when you ask, and answers with Gemini Nano on the phone, any OpenRouter model, or a model on your own Mac. Agent voice adds a real-time LiveKit agent that can see a screen you share. Guides: [second brain](https://github.com/Caceras/my-surface-app/blob/main/docs/second-brain.md) · [agent voice](https://github.com/Caceras/my-surface-app/blob/main/docs/agent.md).

- **Phone assistant:** Settings → *Phone assistant* explains the two steps. Choose Ægentica under Default apps → *Digital assistant app* (this replaces Gemini there). Then swipe up from a bottom corner, or hold the power button, in any app. An ask bar opens at the bottom of the app, styled after the awesome-SEO ask bar: ask about the screen (text and screenshot), by typing or speaking. Copy, Save, Share, Read aloud or *Talk live* follow. Links and phone numbers in the answer become buttons. Nothing is ever tapped or typed in the other app.
- **OpenRouter and local models:** Connected AI has three presets.
  - *Gemini* keeps its own key, so Live voice and Transcribe still work when chat uses another provider.
  - *OpenRouter* gives one key for hundreds of models, with a searchable model list showing what each can see or hear and its price. Providers that store prompts are skipped.
  - *Local* reaches Ollama or LM Studio on your Mac through Tailscale.
  - Answers now stream. Turn on *Also use it for voice and the screen assistant* to talk to your chosen model.
- **Agent voice (AI page):** real-time voice with your own LiveKit agent. It runs any OpenRouter model, or Gemini Live, and searches your notes. It can see a screen you share while you use other apps, and suggests timers, alarms, events, places, calls, searches and notes that run only when you tap them. It needs a small server you approve first; [setup](https://github.com/Caceras/my-surface-app/blob/main/docs/agent.md).
- **Talk about this screen:** from the assistant, *Talk live* starts Gemini Live with the screenshot and screen text already shared.
- **Earlier in this preview:** Record audio and speaker-labelled Gemini Transcribe (build 183); Live voice, Insights and the Transcribe tile (build 180); Transcribe, Polish, recall and the Markdown vault (build 176).

Try on the Pixel: Settings → *Phone assistant* → *Open settings* → choose Ægentica AI. Open any app, swipe up from a bottom corner, and tap *Explain this*. Then Connected AI → *OpenRouter* → paste a key with a credit limit → turn on voice and the assistant, and ask again. Signing continuity is still not provisioned: export your workspace before any reinstall.
