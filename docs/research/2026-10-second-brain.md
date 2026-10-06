# Build a Verbatim-First Second Brain on Your Pixel

> **Research snapshot, 5–6 October 2026.** Prices, model IDs and availability change quickly; re-check the linked sources before acting. This report describes the app *before* the transcription iteration. Since then the app has gained an explicit microphone-service **Transcribe** mode, AI polish with a protected verbatim original, automatic note recall, a Gemini preset and a Markdown vault export. See [the second-brain guide](../second-brain.md) for what shipped, how to use it and what remains.


Yes. Everything you describe can be built as a native Kotlin app on your Pixel 10 Pro XL in October 2026, and Ægentica AI already has the part most products get wrong: a local SQLite database (a single-file database inside the app) that keeps an immutable "original" next to the edited text. Three pieces are missing. The first is a background recorder that keeps the raw audio as the true verbatim record. The second is Swedish speech-to-text. The third is a cloud tier for the jobs the phone can't do. The phone's built-in Gemini Nano is free and private, but it only runs while your app is on screen, reads about 4,000 tokens (word-pieces, roughly 3,000 English words) per request, and has no published Swedish quality. Google's only on-device Swedish transcription is an alpha "Advanced" mode limited to Pixel 10 and 11. The cloud Gemini API covers the rest: Swedish transcription with speaker labels for about $0.30 per audio hour, million-token reasoning, and real-time Swedish voice conversations that can call functions in your app for about half a US cent per minute you speak. A heavy cloud month therefore costs about $10, and an on-device-only month costs nothing. Some things can't be done however much effort you put in: reading Pixel Recorder or Call Notes data, recording phone calls inside your app, being invoked from the Gemini overlay, using Gemini's "Personal Intelligence" context, and running Nano in the background. Google's own Magic Cue, Call Notes, Pixel Screenshots and Personal Intelligence aren't offered in Sweden at all, which is exactly why a Swedish-first app of your own is worth building. The recommended design has one append-only event log on the phone as the system of record. AI layers are stored separately and always trace back to the original. Plain-Markdown copies go to a private GitHub repo, and optionally to a Drive folder for NotebookLM. Neo4j and AlloyDB are not recommended: at one-person scale they add cost or fragility and no benefit. On the legal side, Swedish criminal law lets you record conversations you take part in. Once recordings touch agency work, GDPR applies in full, so personal and work capture should be separate modes from day one.

## Sweden is shut out of Google's own second-brain features

Google has built much of a second brain into the Pixel 10, but almost none of it is available in Stockholm:

- **Magic Cue** (proactive suggestions drawn from messages, mail and the screen) ships in nine countries, and Sweden isn't one of them ([Pixel Help](https://support.google.com/pixelphone/answer/16508057?hl=en)).
- **Pixel Screenshots** supports English, German and Japanese in ten countries, not including Sweden ([Pixel Help](https://support.google.com/pixelphone/answer/15312581?hl=en)).
- **Call Notes** (on-device call transcripts and summaries) is limited to Australia, Canada, Ireland, the UK, the US, Japan and India ([Phone Help](https://support.google.com/phoneapp/answer/15257579?hl=en)).
- **Take a Message** lists Sweden as audio-only, with no transcription ([Phone Help](https://support.google.com/phoneapp/answer/16515613?hl=en-GB)).
- **Pixel Recorder** works, but its real-time transcription has no Swedish. **Speaker labels are US English only**, and summaries cover seven languages, none of them Swedish. Swedish only arrives through "Transcribe again", which "may process audio files on Google servers" ([Recorder transcription](https://support.google.com/pixelphone/answer/16267698?hl=en); [speaker labels](https://support.google.com/pixelphone/answer/16269004?hl=en)).
- **Personal Intelligence** in Gemini, which connects Gmail, Photos and Search, excludes the EEA (the EU plus Norway, Iceland and Liechtenstein) with no timeline ([Dataconomy](https://dataconomy.com/2026/04/15/google-expands-personal-intelligence-globally-to-all-gemini-languages/)).
- **Gemini's agent that operates other apps** launched on Pixel 10 only in the US and Korea ([Google blog](https://blog.google/innovation-and-ai/products/gemini-app/android-multi-step-tasks/)).

None of these apps has a developer API (a documented way for other apps to read their data). Data leaves them only when you share or export it as audio, .txt, Google Docs or NotebookLM ([Recorder share help](https://support.google.com/pixelphone/answer/16267696?hl=en)).

Commercial note-takers don't close the gap either:

- **Otter** transcribes six languages, not Swedish, from $16.99/month ([Otter](https://otter.ai/pricing)).
- **Mem**'s voice mode is English-only ([Mem](https://help.mem.ai/features/voice-mode.md)).
- **Notion** transcribes Swedish, but needs a Business plan and records only the phone's microphone on mobile ([Notion](https://www.notion.com/help/ai-meeting-notes)).
- **Limitless** was bought by Meta, shut off service in the EU on 5 December 2025 and deleted EU data two weeks later ([Limitless](https://limitless.ai/)). That's a reminder that a second brain held by a vendor can disappear.

The leading products do get one thing right, and it's worth copying:

- Mem attaches the original audio under the cleaned-up note ([Mem](https://help.mem.ai/features/voice-mode.md)).
- Granola shows your own words in black and AI additions in gray ([Granola](https://www.granola.ai/blog/granola-pricing-privacy-tradeoff)).
- NotebookLM answers with citations that jump to the exact passage ([Google blog](https://blog.google/technology/ai/notebooklm-audio-video-sources/)).
- Tana keeps the audio so it can re-transcribe later ([Tana](https://outliner.tana.inc/learn/features/tana-meeting-notetaker)).

**None of the products researched documents a side-by-side comparison of verbatim and polished text.** Your immutable-original design is a real differentiator, not a reinvention.

## On the phone, Gemini Nano is private and free but only works on screen

### Nano reads about 4,000 tokens, understands images, and has no documented Swedish quality

Your Pixel 10 Pro XL runs Gemini Nano **nano-v3**. Nano-v3 is built on the Gemma 3n architecture and runs through AICore, the Android system service that hosts on-device models. The newer nano-v4 ships to consumers only on the Pixel 11 and Samsung's newest foldables. On a Pixel 10 you can only get it through the AICore Developer Preview ([ML Kit GenAI](https://developers.google.com/ml-kit/genai)).

The latest Prompt API is still the `1.0.0-beta4` your app already uses, released 21 July 2026, and nothing newer exists ([Google Maven](https://dl.google.com/dl/android/maven2/com/google/mlkit/genai-prompt/maven-metadata.xml)). Since beta3 it has added:

- structured output, where the model fills in a typed Kotlin object instead of free text
- system instructions
- a thinking mode
- multi-image input

Source: [ML Kit release notes](https://developers.google.com/ml-kit/release-notes).

Four hard limits shape the whole design:

- Input should stay **under about 4,000 tokens**.
- Inference is **only allowed while your app is the top app on screen. Even a foreground service gets `BACKGROUND_USE_BLOCKED`.**
- AICore enforces per-app and daily battery quotas whose sizes Google doesn't publish ([ML Kit GenAI](https://developers.google.com/ml-kit/genai); [error codes](https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode)).
- Nano refuses to run on a phone with an unlocked bootloader, so never root this device ([Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)).

One independent benchmark on a 10 Pro XL measured nano-v3 at **9.6 tokens per second**, against about 19 for the preview "Nano 4 Fast" model ([Android Authority](https://www.androidauthority.com/gemini-nano-4-benchmarks-3655763/)).

Three gaps matter for you.

**Swedish polish has to be home-made.** Google's ready-made Summarization and Proofreading APIs **exclude Swedish** ([Summarization](https://developers.google.com/ml-kit/genai/summarization/android); [Proofreading](https://developers.google.com/ml-kit/genai/proofreading/android)). All Swedish polishing has to go through the general Prompt API with your own Swedish instructions. Google publishes no Swedish quality figures, so test it on your own notes before relying on it.

**No audio input and no tool calling.** The Prompt API takes text and images but documents neither. Tool calling means letting the model trigger app functions. You can approximate it by asking for structured output with an action name plus arguments, which your app then checks before doing anything.

**Long recordings need chunking.** Google's own pricing assumes about 175 transcript tokens per spoken minute ([Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing)). At that rate, one Nano call holds roughly 20 minutes of talk, so longer recordings have to be summarized in pieces.

If those limits get in the way, the fallback is to bundle the open **Gemma 4 E2B** model with LiteRT-LM, Google's open-source on-device runtime with a stable Kotlin API. Gemma 4 E2B accepts audio, has a 128K-token context and native function calling, and is not subject to AICore's quotas or its on-screen rule ([Gemma docs](https://ai.google.dev/gemma/docs/core); [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)). The price is a download of about 4 GB, and the app has to manage the battery cost itself.

### Swedish speech-to-text on the device exists, but only as an alpha

Google's ML Kit GenAI Speech Recognition has been in alpha since 28 January 2026 and has two modes ([GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android)):

- **Basic** lists 15 locales, **none of them Swedish**.
- **Advanced** uses the on-device Gemini model, **runs only on Pixel 10 and 11, and lists `sv-SE`** alongside `en-US`.

Advanced mode streams partial text that then settles into final text. It doesn't document punctuation, timestamps or a maximum length. Audio files must be fed in at real-time speed, so re-transcribing an hour of audio takes an hour.

Your app currently uses Android's built-in `SpeechRecognizer`. It has more controls on API 33–34, including automatic punctuation, word timing, segmented long sessions and switching between Swedish and English. Whether it supports Swedish offline on your phone can only be checked at runtime with `checkRecognitionSupport` ([RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent); [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)).

The best open Swedish model is KBLab's **KB-Whisper**. Its small version beats OpenAI's largest Whisper model on Swedish ([arXiv](https://arxiv.org/abs/2505.17538)). It can run on the phone through sherpa-onnx or whisper.cpp, but nobody has published how fast it runs on the Pixel 10's Tensor G5 chip.

No Google on-device API offers diarization (labelling who spoke when). sherpa-onnx's Kotlin pipeline does it offline with a 1.5 MB pyannote model. Avoid its "reverb" model, which carries a non-commercial license ([sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html)).

### Recording for hours is allowed; AI processing in the background is not

Android allows unlimited recording through a **microphone foreground service**, a background task that must show a permanent notification. Unlike data-sync services it has no 6-hour cap. It must be started while your app is visible, or from a tap on its notification, widget or Quick Settings tile, and never at boot ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types); [background-start rules](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)). Pixel Recorder appears to use the same public permissions and caps one recording at 18 hours ([Recorder help](https://support.google.com/pixelphone/answer/16267367); [Bayton system-app database](https://sysapps.bayton.org/packages/com.google.android.apps.recorder)).

Other apps can take the microphone away. Two ordinary apps can never record at the same time, and the preinstalled Assistant always wins ([audio sharing rules](https://developer.android.com/media/platform/sharing-audio-input)). The app therefore has to show you when it has been silenced.

A recording session qualifies as an Android **Live Update**, a pinned, prominent progress notification. Google's policy puts quick-capture shortcuts in tiles and widgets instead ([Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)).

At the time of this research the app only declared a media-playback service; the transcription iteration added the microphone service, but it still stores recognised text rather than raw audio chunks. Together with Nano's on-screen rule it gives the core pattern:

1. Record continuously to disk in small chunks.
2. Transcribe and polish whenever the app is open.
3. Never make saving the verbatim record wait on AI.

### A sideloaded app can see more of your phone than Play Store apps, with explicit permission

The strongest legitimate hook is making your app the **default digital assistant**. Long-pressing the power button then opens your app with the current screen's text and a screenshot. The role also lets the app start the microphone service from the background. The cost is that Gemini no longer opens from that gesture ([VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession); [background-start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).

Several other sources of context are available:

- notification access, with one-time codes redacted since Android 15
- usage access (which apps you used and when)
- your calendar
- Android 17's new Contacts Picker
- screen capture, with your consent each session

Because your app is installed from an APK file rather than the Play Store (sideloaded), Android's "restricted settings" guard applies. You have to tap **Allow restricted settings** once in App info before notification, usage or accessibility access can be granted ([Android Help](https://support.google.com/android/answer/12623953); [AOSP source](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/service/java/com/android/ecm/EnhancedConfirmationService.java)).

Two Google mechanisms stay out of reach:

- **Private Compute Core**, the protected area behind Magic Cue, is closed to third-party apps ([permission reference](https://developer.android.com/reference/android/Manifest.permission)).
- **AppFunctions**, Android's on-device counterpart to MCP (Model Context Protocol, an open standard for letting AI assistants call tools), would let your app offer actions such as "search notes" or "start capture" to Gemini. It is still at alpha12, and **Gemini integration is a private preview for trusted testers**, so treat it as a later, optional bet ([AppFunctions](https://developer.android.com/ai/appfunctions)).

Sideloading stays open until Google's developer verification extends to all countries in 2027. A free "limited distribution" account covering up to 20 devices launched in August 2026 ([developer verification](https://developer.android.com/developer-verification)).

### Framework Views remain a defensible, fully native choice

Google now states that "Android is Compose-first" and has put the View toolkit your app uses into maintenance mode, with only critical fixes ([Compose-first](https://developer.android.com/develop/ui/compose/first)). That doesn't make Views any less native: the underlying `android.view` layer still sits beneath Compose. But Material 3 Expressive, Google's newest visual style, exists only in Compose Material3 `1.5.0-alpha29` (23 September 2026), and all new Android Studio UI tooling is Compose-only ([Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)).

The best case for rewriting in Compose is that upkeep gets cheaper over time. Against it:

- a second brain's value lies in capture and data, not the UI toolkit
- Expressive is still alpha
- your repo's own rules call for keeping Views

**Recommendation:** keep Views, and revisit when Material3 1.5 goes stable.

The upgrade that does matter is moving the app's compile and target SDK from 36 to **37 (Android 17)**. Android 17 reached Pixels on 16 June 2026 ([Wikipedia](https://en.wikipedia.org/wiki/Android_17)). Targeting it unlocks Handoff (continue on another device), a dedicated assistant audio channel and colored Live Updates ([Android 17 features](https://developer.android.com/about/versions/17/features)). The current stable toolchain is Kotlin 2.4.20 with Android Gradle Plugin 9.4.0, which supports up to API 37 ([Kotlin](https://kotlinlang.org/docs/releases.html); [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes)).

## In the cloud, Gemini fills every gap, and your existing client can already talk to it

### Models to use and avoid

Gemini's generally available (GA) lineup is now the 3.x Flash family, and both recommended models take up to 1,048,576 tokens of input, enough to fit years of notes in one request:

- **`gemini-3.8-flash`** (GA 2 September 2026) is the capable default.
- **`gemini-3.5-flash-lite`** is the cheap default.

Sources: [Models](https://ai.google.dev/gemini-api/docs/models); [Changelog](https://ai.google.dev/gemini-api/docs/changelog).

Avoid:

- **Gemini 2.5**, which since 18 September 2026 is restricted to existing users.
- **`gemini-3.1-flash-lite`**, which shuts down on 7 May 2027.

Use exact model IDs rather than `-latest` aliases, which Google swaps with two weeks' notice ([Deprecations](https://ai.google.dev/gemini-api/docs/deprecations)).

### Transcription with speaker labels

For Swedish meetings the standout is **`gemini-3.5-transcribe`** (GA 26 August 2026). It offers:

- 85+ locales including `sv-SE`, with automatic handling of Swedish–English mixing
- diarization for up to 8 speakers
- word timestamps
- a custom vocabulary of up to 1,000 phrases for client names and SEO jargon

It costs a blended **~$0.005 per minute** ([Transcribe](https://ai.google.dev/gemini-api/docs/transcribe); [Pricing](https://ai.google.dev/gemini-api/docs/pricing)). Its limits:

- one hour per request, or 30 minutes with diarization or timestamps turned on
- the custom vocabulary can't be combined with diarization

The alternatives fall short. Google's Chirp 3 speech service supports Swedish, but not Swedish diarization, and costs about three times more at standard rates ([Chirp 3](https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3); [Speech-to-Text pricing](https://cloud.google.com/speech-to-text/pricing)). Plain Flash-Lite can also transcribe, up to 9.5 hours per prompt, for about $0.06 per hour. It only labels speakers if you ask it to in the prompt, so the labels are less reliable ([Audio understanding](https://ai.google.dev/gemini-api/docs/audio)).

### Real-time voice

For the real-time "talk to my brain" window, use **`gemini-3.8-live`** (GA 15 September 2026). It:

- streams audio both ways over a WebSocket (a connection that stays open in both directions)
- returns live transcripts of both sides
- supports Swedish among 99 languages
- can call your app's functions mid-conversation, by default without pausing the conversation
- can use Google Search

Sources: [gemini-3.8-live](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live); [Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities).

It costs **$0.005 per minute of your speech and $0.018 per minute of its speech** ([Pricing](https://ai.google.dev/gemini-api/docs/pricing)). Plan for four practical constraints:

- You can't set a language code, so the system prompt has to say "svara alltid på svenska" (always answer in Swedish).
- Audio-only sessions stop at 15 minutes unless context compression is turned on.
- Connections drop after about 10 minutes and have to be resumed with a token that stays valid for two hours.
- File Search isn't available in Live mode.

Sources: [session management](https://ai.google.dev/gemini-api/docs/live-session); [File Search](https://ai.google.dev/gemini-api/docs/file-search).

This is the right pattern for asking your notes questions by voice. The model asks for something, your app runs an approved local search, and the result goes back to the model as data.

### Search, retrieval and embeddings

All 3.x Flash models support function calling, URL context, code execution and Google Search grounding. Search grounding means answers backed by live search; you get 5,000 free queries a month, then pay $14 per 1,000.

They also support **File Search**, Google's managed retrieval service. Retrieval, often called RAG, means fetching relevant documents and handing them to the model. Storage is free, but Google keeps files indefinitely and doesn't index audio ([File Search](https://ai.google.dev/gemini-api/docs/file-search)).

Embeddings are numeric fingerprints of meaning used for semantic search. You have two options:

| Option | Where it runs | Cost |
|---|---|---|
| **`gemini-embedding-2`** | Google's cloud | $0.20 per million text tokens ([Embedding 2](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-2)) |
| **EmbeddingGemma** | On the phone; ~200 MB download, 100+ languages | Free ([EmbeddingGemma](https://ai.google.dev/gemma/docs/embeddinggemma)) |

Gemini's Interactions API can connect remote MCP servers directly. Gemini itself can therefore read a GitHub repo through GitHub's official MCP server, and your phone app never has to implement MCP ([Function calling](https://ai.google.dev/gemini-api/docs/function-calling); [GitHub MCP server](https://github.com/github/github-mcp-server)).

### How the app connects

**The cheapest first step needs no new code.** Your connected-AI client speaks the OpenAI format, and Gemini offers an **OpenAI-compatible endpoint** at `https://generativelanguage.googleapis.com/v1beta/openai/`, still labelled beta. It supports chat, streaming, tools, structured output, embeddings and WAV/MP3 audio input. It does not support Live voice, the Transcribe model, File Search or MCP ([OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)). For those, the app calls Gemini's own REST and WebSocket interfaces directly from Kotlin.

Google's approved route for apps without a server, Firebase AI Logic, is a poor fit here:

- It **requires App Check from 2 November 2026**. App Check is an integrity check that blocks unverified app builds.
- Its Live support is in preview.
- Its docs list only the older `gemini-3.1-flash-live-preview` model.

Sources: [Firebase App Check](https://firebase.google.com/docs/ai-logic/app-check); [Firebase Live](https://firebase.google.com/docs/ai-logic/live-api).

Google warns never to ship API keys inside apps ([API keys](https://ai.google.dev/gemini-api/docs/api-key)). That is right for an app distributed to others. For a build only you use, the practical option is "bring your own key": you paste your own AI Studio key into the app's Keystore-protected storage (Android's hardware-backed vault, which the app already uses) and set a spending cap on the project.

### Free tier and EEA terms

The free tier covers Flash, Flash-Lite, Live, Transcribe and Embedding 2 at no charge. Its rate limits are only shown inside AI Studio ([Pricing](https://ai.google.dev/gemini-api/docs/pricing); [Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)). Because Sweden is in the EEA, Google applies **paid-tier data terms even to free use**. Your prompts aren't used to improve Google's products, although they are logged for a limited period to detect abuse ([Gemini API terms](https://ai.google.dev/gemini-api/terms)).

Two conditions in the same terms matter:

- The API is "for professional or business purposes, not for consumer use".
- "You may use only Paid Services when making API Clients available to users in the European Economic Area". The day colleagues start using the app, it needs a billed project.

Source: [terms](https://ai.google.dev/gemini-api/terms).

## What is realistic and what isn't on a Pixel in Sweden in October 2026

| What you want | Verdict | Why |
|---|---|---|
| Fully native Kotlin app with framework Views on Android 17 | **Realistic** | Views are in maintenance mode but supported; no rewrite needed ([Compose-first](https://developer.android.com/develop/ui/compose/first)) |
| Record for hours with the screen off | **Realistic** | A microphone foreground service has no time limit; it must be started from visible UI, a notification, a widget or a tile ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types)) |
| Start capture without opening the app | **Partly** | Tile, widget and notification taps work; starting fully in the background needs the default-assistant role |
| Swedish live transcription on the phone | **Realistic, alpha** | ML Kit Advanced mode lists `sv-SE` on Pixel 10/11; Basic mode has no Swedish ([ML Kit Speech](https://developers.google.com/ml-kit/genai/speech-recognition/android)) |
| On-device AI polish that never touches the original | **Realistic** | Prompt API with structured output; the original is a separate record; Swedish quality is untested |
| Nano working in the background, or on an hour of transcript at once | **Not possible** | On-screen only, ~4,000-token input; chunk it or use the cloud ([ML Kit GenAI](https://developers.google.com/ml-kit/genai)) |
| Nano listening to audio or calling tools natively | **Not via ML Kit** | Undocumented; possible with bundled Gemma 4 E2B (~4 GB) via LiteRT-LM |
| Swedish speaker labels | **Realistic** | Cloud `gemini-3.5-transcribe` (up to 8 speakers, chunks of 30 minutes or less) or on-device sherpa-onnx; Recorder labels are US English only |
| Real-time Swedish voice chat that searches your notes | **Realistic** | `gemini-3.8-live` with function calls into the app, plus session resumption |
| Understanding photos, screenshots and PDFs | **Realistic** | Nano takes images; cloud Flash takes image, video, audio and PDF |
| Screen context on demand | **Realistic, with a trade-off** | The default-assistant role takes over Gemini's power-button gesture |
| Context from notifications and app usage | **Realistic** | After a one-time "Allow restricted settings"; one-time codes are redacted |
| Reading Recorder, Call Notes, Journal, Screenshots or Magic Cue data | **Not possible** | No API; only manual share or export |
| Recording phone calls in your app | **Not possible** | Blocked for third-party apps since Android 10 and by Play policy since 2022 ([Engadget](https://www.engadget.com/google-is-banning-third-party-call-recording-apps-from-the-play-store-093201443.html)); use Google Phone's recording, which announces itself to everyone on the call, and share the file in ([Phone Help](https://support.google.com/phoneapp/answer/9803950?hl=en-GB)) |
| The Gemini overlay invoking your app | **Not yet** | AppFunctions is a private preview for approved apps |
| Gemini Personal Intelligence or Pixel's Private Compute Core | **Not possible** | No API; the EEA is excluded; internal-only permission |
| GitHub as both source and destination | **Realistic** | Fine-grained token, Contents API, official MCP server |
| Google Drive as both source and destination | **Realistic, with limits** | `drive.file` scope plus the Google Picker; full-Drive access triggers Google's verification limits ([Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)) |
| An Obsidian vault the app writes into | **Realistic** | One-time folder permission under `Documents/`, not Obsidian's private storage |
| Neo4j or AlloyDB as the core database | **Possible, not worth it** | Free graph tiers pause or delete idle databases; AlloyDB costs ≈ $227/month |

## Architecture: one append-only log, with everything else rebuilt from it

Start from your own requirement: the first input must survive verbatim. That makes the core of the system an **append-only event log**, a table where records are only ever added and never edited or deleted. Every capture becomes an event: a typed note, an audio chunk, a transcript, an imported Recorder file. Each event carries a timestamp and a content hash, a fingerprint that proves the text hasn't changed. A corrected or re-run transcript becomes a new event that points to the one it replaces.

Everything AI produces lives in a separate **derivations** table: the polish, title, summary, tags, people and projects, and embeddings. Each derivation records which model made it, the prompt version, and exactly which span of which event it came from. This is the pattern the Graphiti/Zep memory engine formalizes. Every entity "traces back to the episodes (raw data) that produced it", and outdated facts are marked invalid rather than deleted ([Zep paper](https://arxiv.org/pdf/2501.13956.pdf); [Graphiti](https://github.com/getzep/graphiti)). Google's LangExtract library applies the same source-grounding idea to extraction ([LangExtract](https://github.com/google/langextract)).

Your existing workspace store already keeps "originals independently of edited bodies" alongside full-text search and links. Phase 1 below extends that store with this design rather than replacing it.

```
CAPTURE (phone)                      VERBATIM LOG (phone, SQLite)           DERIVED LAYERS (rebuildable)
 microphone service → audio chunks ─► events: append-only, hashed ───────►  polish · title · tags · summary
 typed note / share sheet / photo ──►  transcript v1, v2 … never edited      people & projects (edges) · embeddings
                                                                              each tagged: model + prompt + source span
BRAINS (chosen per task)                                                  WINDOW (recall)
 Nano on device: free, private, on-screen only, ~4K tokens                 full-text + semantic search + links
 Gemini cloud via your own key: Transcribe, 1M context, Live voice         Live voice → approved functions → cited answers
MIRRORS (plain text, one-way, rebuildable)
 Markdown vault (Obsidian format) → private GitHub repo (history, MCP) → optional Drive folder → NotebookLM
```

| Layer | Recommended choice | Rejected alternative and why |
|---|---|---|
| System of record | The app's own SQLite: `events`, `derivations`, `entities`, `edges` | Graph database: Neo4j AuraDB Free pauses after 72 idle hours ([Neo4j](https://graphacademy.neo4j.com/courses/aura-fundamentals/1-introduction/2-tiers/)); FalkorDB Free deletes after 7 idle days ([FalkorDB](https://docs.falkordb.com/cloud/free-tier)); Kùzu was archived after Apple's acquisition ([GitHub](https://github.com/kuzudb/kuzu); [MacDailyNews](https://macdailynews.com/2026/02/12/apple-acquires-graph-database-maker-kuzu/amp/)) |
| Relations (knowledge graph) | An `edges` table (from, relation, to, valid-from/to, evidence) plus Obsidian `[[wikilinks]]` for a visual graph | AlloyDB: no free tier, a 30-day trial, then ≈ $227/month ([Google Cloud](https://docs.cloud.google.com/alloydb/docs/free-trial-cluster); [Bytebase](https://www.bytebase.com/dbcost/alloydb-pricing/)) |
| Semantic search | Embeddings stored in the same database and compared directly; a personal corpus is small enough | sqlite-vec is still alpha and needs a custom SQLite build; ObjectBox's latest is a 6.0 beta ([sqlite-vec](https://github.com/asg017/sqlite-vec); [ObjectBox](https://github.com/objectbox/objectbox-java)) |
| Human-readable copy | A Markdown vault written into `Documents/SecondBrain` through a one-time Android folder permission | Obsidian's private app storage is unreachable; Drive can't be mounted as a folder ([Android storage docs](https://developer.android.com/training/data-storage/shared/documents-files); [CommonsWare](https://commonsware.com/blog/2019/11/09/scoped-storage-stories-trees.html)) |
| Off-phone copy and history | A private GitHub repo, written with a token limited to that one repo | Supabase Stockholm (500 MB free, pauses after a week idle) is kept for later, only if you need search across devices ([Supabase](https://supabase.com/pricing)) |
| Google AI access | A Drive folder using only the narrow `drive.file` scope, for NotebookLM | Full-Drive scope: limited to 100 users and re-consent every 7 days in testing mode ([Google Cloud Help](https://support.google.com/cloud/answer/15549945)) |

### The mirrors

The mirrors are what turn a phone app into a second brain you can reach from anywhere.

**Obsidian.** The app should write Markdown that Obsidian reads natively: YAML properties at the top of each file, quoted `"[[links]]"` inside those properties, and `aliases` for alternate names of people. Obsidian's Bases feature then shows people, projects and meetings as tables with no database at all ([Obsidian properties](https://obsidian.md/help/properties); [Bases](https://obsidian.md/help/bases)). Obsidian is free. Syncing to a desktop costs $4/month for Obsidian Sync, or nothing with the maintained Syncthing-Fork app ([Obsidian pricing](https://obsidian.md/pricing); [Syncthing-Fork](https://github.com/researchxxl/syncthing-android)).

**GitHub.** Fine-grained personal access tokens (password-like keys limited to chosen repos) can be restricted to a single repo ([GitHub changelog](https://github.blog/changelog/2024-10-18-new-pat-rotation-policies-preview-and-optional-expiration-for-fine-grained-pats/)). Three practical rules:

- Writes through GitHub's Contents API must be sent one at a time ([Contents API](https://docs.github.com/en/rest/repos/contents)), so the app should batch commits.
- A directory can hold at most 3,000 files, so sort files into year and month folders ([repository limits](https://docs.github.com/en/repositories/creating-and-managing-repositories/repository-limits)).
- Keep audio out of Git.

The payoff is large. Every change becomes an auditable commit, and **GitHub's official MCP server makes the vault readable by Claude, ChatGPT or Gemini on your desktop** ([GitHub MCP server](https://github.com/github/github-mcp-server)).

**NotebookLM.** NotebookLM imports Drive files, and Drive sources now re-sync every few minutes. The free tier allows 50 sources per notebook, each up to 500,000 words ([NotebookLM Help](https://support.google.com/notebooklm/answer/16215270)), so one rolled-up Google Doc per month works better than thousands of small files.

### Safety rule for AI actions

The design keeps one rule your repo already follows: model output is data, never a command. In Live voice, the model can only call a short list of read-or-append functions:

- search notes
- open note
- draft a note

Anything with side effects waits for your tap. Every AI change is a proposal shown in a tinted color until you accept it.

## Roadmap: five phases, each useful on its own

| Phase | Outcome | What gets built | Go/no-go test before the next phase |
|---|---|---|---|
| **0. Bake-off** (days, $0) | Know what Swedish quality you get for free | Point the existing connected-AI client at Gemini's OpenAI-compatible endpoint (`gemini-3.5-flash-lite`, `gemini-3.8-flash`). On the phone, run `checkRecognitionSupport` for offline `sv-SE`, enable ML Kit Advanced speech, and polish 20 real notes with Nano. Transcribe the same 3 recordings on-device and with `gemini-3.5-transcribe` | For each task (dictation, meetings, polish), decide whether on-device is good enough or the cloud is needed |
| **1. Verbatim capture** | Never lose a word | Microphone foreground service with a Live Update notification and tile/widget start; audio saved in chunks; append-only `events` table with hashes; live transcript; Verbatim/Polished views with AI text tinted and a diff view; upgrade to API 37 | A crash mid-recording loses at most one chunk; the original is byte-identical after every edit (automated test) |
| **2. AI layers** | Polish, titles, people and projects | `derivations` table recording model, prompt version and source span; Nano polish while the app is open; `gemini-3.5-transcribe` for diarized meetings in chunks of 30 minutes or less; suggested people/projects you accept or merge into links; embeddings for meaning-based search | Every AI sentence traces back to an event; re-running a model never changes the original |
| **3. Mirrors** | Your brain outside the phone | Markdown vault export in Obsidian format; batched commits to a private GitHub repo; optional Drive folder for NotebookLM | Obsidian on the desktop, Claude or ChatGPT through GitHub MCP, and NotebookLM all answer with citations from the mirror |
| **4. Voice window** | Talk to your brain | `gemini-3.8-live` over WebSocket with the approved function list, a Swedish system prompt and session resumption; optionally the default-assistant role for screen context | A spoken Swedish question gets a cited answer in seconds; no function with side effects runs without a tap |
| **5. Only if needed** | Wider context and reach | Notification and usage context, AppFunctions, Gemma 4 on device, a Supabase Stockholm index, Compose. If colleagues adopt the app: a billed project, a small server or Firebase, and a data processing agreement | Each item needs a concrete trigger before it gets built |

The order follows risk.

**Phase 0 answers the two questions nobody has published data on:** how good Nano's Swedish polish is, and how on-device and cloud Swedish transcription compare on your own recordings. Those answers decide how much you will ever pay the cloud.

**Phase 1 comes before any AI** because the verbatim record is the only part you can't recreate later. Every AI layer can be regenerated as models improve.

**The mirrors (phase 3) come before real-time voice (phase 4)** because, once mirrored, the vault is immediately useful to the desktop AI tools you already use. Live voice is the most engineering-heavy part and the one most likely to change as Google's Live API evolves. Its documentation still mixes 3.8 and older 2.5-era samples ([Live tools](https://ai.google.dev/gemini-api/docs/live-tools)).

Under your repo's rules, new runtime libraries (ML Kit speech, MediaPipe, a WebSocket client such as OkHttp) belong in the Nano flavor's source set. The core stays framework-only.

## What it costs: $0 on the device, about $10 for a heavy cloud month

| Item | Price (USD, Oct 2026) | Likely monthly cost for you |
|---|---|---|
| Gemini Nano, ML Kit speech, Android recognizer, EmbeddingGemma (all on device) | $0; battery quotas apply | $0 |
| Gemini API free tier (Flash, Flash-Lite, Live, Transcribe, Embedding 2) | $0, rate-limited ([Pricing](https://ai.google.dev/gemini-api/docs/pricing)) | $0 |
| `gemini-3.5-transcribe` | ≈ $0.005/min ≈ $0.30 per audio hour | 20 h of meetings ≈ $6 |
| `gemini-3.8-live` | $0.005/min of your speech, $0.018/min of its speech | 30 sessions of ~13 min ≈ $3 |
| `gemini-3.5-flash-lite` | $0.30 in / $2.50 out per million tokens (≈ $0.06 per audio hour) | Under $1 |
| `gemini-3.8-flash` | $0.75 / $3.75 per million tokens until 31 Dec 2026, then $1.50 / $7.50 | ≈ $1–2 |
| `gemini-embedding-2` | $0.20 per million text tokens | Under $0.25 |
| Google Search grounding | 5,000 free queries/month, then $14 per 1,000 | $0 |
| Private GitHub repo, Obsidian app, Syncthing-Fork | $0 | $0 |
| Obsidian Sync (optional) | $4/month billed yearly, $5 month-to-month ([Obsidian](https://obsidian.md/pricing)) | $0–5 |
| Supabase (optional index in Stockholm) | Free tier 500 MB, pauses after 7 idle days; Pro $25/month ([Supabase](https://supabase.com/pricing)) | $0 |
| Neo4j AuraDB | Free tier pauses after 72 h idle; Professional ≈ $66/month at 1 GB ($0.09 per GB-hour) ([Neo4j](https://neo4j.com/pricing/)) | Not recommended |
| AlloyDB | No free tier; ≈ $227/month minimum at US list price | Not recommended |
| For comparison: Otter Pro, Granola Business, Plaud Note Pro | $16.99/month (no Swedish); $14/user/month; $189 device plus $17.99/month for the Pro plan ([Otter](https://otter.ai/pricing); [Granola](https://www.granola.ai/blog/granola-pricing-privacy-tradeoff); [Laxis](https://www.laxis.com/de/blog/plaud-note-pro/)) | — |

The heavy-month estimate adds up to roughly **$10**. It assumes:

- 20 hours of diarized meeting transcription
- 30 voice sessions of about 10 minutes of your speech and 3 minutes of the model's
- polishing and embedding everything

Live sessions re-bill accumulated context on each turn, which Google doesn't quantify, so long voice sessions cost more than this simple per-minute math suggests ([Pricing](https://ai.google.dev/gemini-api/docs/pricing)). The same month costs **$0** within the free tier's limits, or with on-device processing only. The real cost is engineering time, not subscriptions.

Before the app holds work data, link a billing account and set a project spending cap. Billed Tier 1 projects have a $250 billing cap, and per-project spend caps exist ([Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits); [Changelog](https://ai.google.dev/gemini-api/docs/changelog)).

## Legal and privacy: record only what you're part of, and keep work separate from private life

*This is a summary of primary sources, not legal advice.*

### Recording

Under Brottsbalken 4 kap. 9 a §, secretly recording speech is a crime (olovlig avlyssning) only when you don't take part in the conversation, or got access to it without permission, and the setting isn't open to the public ([Riksdagen](https://www.riksdagen.se/sv/dokument-och-lagar/dokument/svensk-forfattningssamling/brottsbalk-1962700_sfs-1962-700/)). **A participant may lawfully record without telling the others** ([Lawline](https://lawline.se/answers/ar-det-lagligt-att-spela-in-ett-samtal)).

**Never leaving the phone recording a room you have walked out of** therefore becomes a design rule: capture is always started by you and visible in the notification. Spreading a lawful recording can still be a crime, for example olaga integritetsintrång under 4 kap. 6 c § ([Riksdagen](https://www.riksdagen.se/sv/dokument-och-lagar/dokument/svensk-forfattningssamling/brottsbalk-1962700_sfs-1962-700/)).

### GDPR: personal use versus work

GDPR's "household exemption" covers purely personal processing with "no connection to a professional or commercial activity". The EU Court of Justice reads it narrowly, and it never covers the providers who supply the tools ([Recital 18](https://gdpr-info.eu/recitals/no-18/); [Ryneš case](https://eulawradar.com/case-c-21213-rynes-data-from-private-cctv-cameras-overlooking-public-spaces-and-private-homes/)).

Agency meetings and client calls fall under GDPR in full. Your agency is likely the controller (the party legally responsible for the data). That brings a set of obligations:

- a legal basis, most likely legitimate interest
- informing colleagues and clients
- a data processing agreement with the AI vendor
- deletion rules

IMY, the Swedish data protection authority, published a sandbox report in April 2026 that accepted AI transcription under conditions. Those conditions were human review of AI output, clear deletion routines, encryption and access control ([IMY](https://www.imy.se/nyheter/transkribering-med-ai-kan-avlasta-socialtjansten/); [JP Infonet](https://www.jpinfonet.se/kunskap/nyheter4/2026/augusti/rattsliga-forutsattningar-for-ai-transkribering/)).

Keep client data out of the consumer Gemini app. Human-reviewed chats there are kept for up to three years ([Gemini Privacy Hub](https://support.google.com/gemini/answer/13594961?hl=en)). Route work material through the billed Gemini API, which processes data under a data processing addendum ([terms](https://ai.google.dev/gemini-api/terms)).

### Voiceprints and emotion detection

A persistent voiceprint that recognizes a named colleague across recordings counts as biometric special-category data. It needs explicit consent, and EDPB guidance says it shouldn't run as passive background analysis ([EDPB](https://www.edpb.europa.eu/system/files/2021-03/edpb_guidelines_022021_virtual_voice_assistants_adopted-public-consultation_en.pdf)). Speaker labels per recording that you rename yourself, as Pixel Recorder does, are the safe default.

The EU AI Act exempts purely personal use ([Art. 2(10)](https://artificialintelligenceact.eu/article/2/)). At work, however, it has **banned inferring emotions from voice since 2 February 2025** ([Art. 5(1)(f)](https://artificialintelligenceact.eu/article/5/)), so don't build a "meeting mood" feature for the work mode.

## Conclusion

The asset that lasts is the log, not the model. In the past year:

- Gemini 2.0 was shut down and 2.5 was restricted to existing users.
- Kùzu was archived after Apple acquired it.
- Limitless deleted its EU users' data.

Churn is the norm. Storing the model ID and prompt version with every derivation turns each future model into an upgrade rather than a migration: you re-run the AI layers over originals that never changed. "Which AI should I use?" becomes a setting you can change per task.

Sweden's exclusion from Google's features also changes the build-versus-buy question. You aren't duplicating Google. You're building what Google doesn't ship here, and the hardest part, verbatim provenance, is already in your codebase.

The remaining unknowns are practical rather than architectural: how good Nano's Swedish is, and how on-device Swedish transcription compares with the cloud on your own recordings. A few days of side-by-side testing settles both. Once the vault is mirrored to GitHub, the phone is no longer the only way in: any AI assistant that supports MCP can read your second brain too.
