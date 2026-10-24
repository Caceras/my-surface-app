# Current preview changes

**New in this build:**
- **More ways to the genie.** Share anything to **Ask Ægentica** (a link, a picture, a screenshot, a PDF, a text file) and the genie opens over the app with it. Long-press the app icon for **Ask Ægentica**. Add the **Ægentica** tile to Quick Settings and the **Ægentica** widget to the home screen: both say when the genie is working, waiting for you or has an answer ready, and open it with a tap. Write an **NFC tag** in Settings (for example "Plan my morning"): tapping it starts the genie on those words (it still asks before doing anything); tags written on another phone show their words and wait for **Start**.
- **Ægentica keys is a whole keyboard now, with the genie inside.** It can replace Gboard: Svenska with å ä ö (or English), numbers, symbols, emoji, accents on long press, a repeating delete, cursor slide on the space bar, an Enter key that searches or sends when the app wants it to, and dictation. Tap **Æ** (or the rabbit) and the keys turn into the genie, working on what you are writing: you see its steps and its answer as it writes, then tap **Insert** or **Replace** (with **Undo**). Type or speak to change what it does. Drag it up for the full genie over the app. Hold the space bar and speak to ask the genie without typing. A quiet ideas line finishes your words and suggests the next one, learned from your own typing on the phone. Turn the genie off in a single app from the panel (*Off in …*). Turn it on in Settings → *Ægentica keys*: **Turn on**, then **Choose keyboard**. Passwords, cards, codes, numbers and incognito fields get plain keys only.
- **Select text, get the genie.** Select text in any app and choose **Ægentica**: the genie sheet opens over the app with your selection, and the agent starts helping. In a text field, **Replace selection** puts the answer back. The separate Listen, Summarise, Proofread, Make professional and Polish entries are gone; ask the genie for any of them. On Ægentica's own pages (aegentica.se, Glimt) **Ægentica** is now the first thing in the selection toolbar, before Google's Translate; in other apps Android keeps its own first item and Ægentica is in the ⋮ menu.
- **Ægentica hands: it uses your phone.** Turn it on in Settings → *Ægentica hands* (Android may first need *Allow restricted settings* in App info). The assistant gesture then opens the genie, a light floating bar with the rabbit, over any app, listening. Ask it to do something: it reads the screen, and the rabbit flies to each button just before it taps, types or scrolls. It asks before acting (*Always in this app* remembers), always asks before sending, calling, paying or deleting, and never types passwords: it asks you to take over. **Stop** is always on screen, and touching the screen pauses it until you tap Continue.
- **The genie bar.** Fold it into the rabbit, drag it anywhere, hold the rabbit to hear what it is doing. Keep the microphone open to steer it while it works.
- **A live notification.** While it uses your apps, the notification shows the current step with Stop, Allow and a reply field; on Android 16 it can show as a live update in the status bar.
- **The assistant thinks with a real agent.** Swipe up from a bottom corner: the sheet now asks Ægentica's own agent on OpenRouter, with your screen's text and screenshot attached, instead of Gemini Nano. Sign in once in Settings → *Phone agent* (or with the *Sign in to OpenRouter* chip in the sheet); Claude Sonnet 5.5 is the default, and the model chip with its maker's logo picks another. Gemini Nano answers only when there is no connection, and the sheet says *Offline*.
- **Voice first.** The sheet starts listening when it opens. Hold the microphone to talk and let go to send. Talk or type while it works to change its course; the round button stops it. Spoken questions are answered aloud.
- **The sheet stays put.** It no longer shrinks or slides under the gesture bar while an answer streams: it keeps clear of the bars and the keyboard and keeps its height until the next question. It is opaque now, in aegentica.se's white and black, with the same message box: + on the left (the screen, Chat on aegentica.se, Talk live, the model, settings), the model, the microphone and send on the right.

**Earlier in this build:**
- **Chat about your screen on aegentica.se.** In the phone's assistant (swipe up from a bottom corner), tap **Chat about this**: a new chat opens on aegentica.se with the screen's text in the message box and the screenshot attached, so the AI sees what you see. Add your question and send; nothing is sent before you do. After an answer, the pencil icon does the same. A selection goes along as text.

**Earlier in build 213:**
- **Ægentica opens aegentica.se.** The phone and the computer are now one Ægentica: the same chats, agents, tasks, memory and models, from your own server. What only the phone can add is lent to the site:
  - **Read aloud** uses your phone's offline voices (HayaiTTS and your other voice apps) when Settings → Voice on aegentica.se is set to *This device*: free, without pauses, in the reply's language. *Phone voice → Choose* opens the phone's voice picker.
  - **The microphone** uses the phone's own offline dictation.
  - **Share to Ægentica** from any app: the text becomes a new chat's draft, never sent by itself.
  - **Live voice** can use the microphone after Android asks once.
  - **Phone** in the side menu opens Glimt for browsing, notes and tasks, recording and transcription, hands-free voice, the reading voice and the phone settings.
  - aegentica.se's design and features come from your server, so improvements arrive without reinstalling the app. Your chats live on the server, so reinstalling the app loses nothing but the sign-in.

**Earlier in build 209:**
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
