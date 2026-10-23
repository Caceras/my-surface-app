# Privacy and data

This describes the implementation, not a guarantee about Android, keyboards, document providers, Google, Beeper or a configured AI provider. See the [everyday guide](everyday-workspace.md) for the controls that select these boundaries.

## Processing choices

Gemini Nano through AICore is the default. Text-selection presets use Nano; hands-free Voice and the screen assistant use Nano unless the owner turns on *Also use it for voice and the screen assistant* for the connected provider. Dictation uses Android's on-device recognizer; **Transcribe** on the Nano build may use Google's on-device GenAI Speech Recognition (alpha, ML Kit) and otherwise Android's on-device recognizer. Spoken output selects installed voices that do not require a network. There is no silent cloud recognition or inference fallback. *Live transcript* stores recognised text with time stamps and no audio. *Record audio* keeps the recording itself in app-private storage only when the user selects that mode and starts it.

**Connected AI is optional.** The user supplies an HTTPS endpoint, model ID and key (the *Use Google Gemini* preset only fills Google's endpoint and model), reviews the provider and enables it for the typed/dictated AI composer and Polish. That provider receives the submitted question or text being polished, bounded recent conversation and selected source excerpts, plus matching Library excerpts only if *Include matching Library excerpts* is enabled. For Gemini users in the EEA, Google's API terms apply paid-service data handling (prompts are not used to improve products but are logged for a limited period for abuse monitoring); Google's terms describe the API as not for consumer use, and offering it to other EEA users requires a billed project. Provider charges and retention rules apply. Redirects are refused. Cleartext traffic is disabled except to `*.ts.net` names, which exist only inside the owner's Tailscale network and are encrypted by it (used for a local model server on the owner's Mac). Each provider's key is stored separately, encrypted with Keystore. OpenRouter requests carry `provider.data_collection: deny`, excluding providers that store or train on prompts. Cancelled requests may already have reached the provider and cannot be recalled.

**Live voice (optional, Nano build):** after a one-time consent and only while the Live screen is open, microphone audio streams over TLS to Google's Gemini Live API with the user's Gemini key. When Gemini calls the read-only note tools, the matching titles, dates and excerpts (or one note's text, up to 4,000 characters) are sent as tool results. Leaving the screen, audio-focus loss or End stops capture; nothing runs in the background. The conversation transcript is saved locally as a note.

**Gemini Transcribe (optional):** only after the user taps *Transcribe with Gemini* on a recording note and accepts a dialog naming the model and estimated cost, each audio part is uploaded over TLS to Google's Files API with the user's Gemini key and transcribed by `gemini-3.5-transcribe` with `store: false` (otherwise Google keeps interactions for up to 55 days). The uploaded file is deleted straight after each part, including on errors and cancellation; Google's Files API would otherwise delete it after 48 hours. Leaving the note cancels the job. The audio stays on the phone.

**Phone assistant (optional):** only after the owner chooses Ægentica as *Digital assistant app* and invokes it (corner swipe or power button), Android hands over the visible text of the current app and a screenshot, subject to the system's *Use text from screen* and *Use screenshot* switches. Password fields are skipped, and secure windows arrive empty. The context is kept in memory only for the open panel: it is never stored, exported or synced, and closing the panel drops it. Questions go to Nano on the phone, or, after the separate voice/assistant opt-in, to the connected provider with the screen text and (for models that see images) the screenshot. One tap on the context chip leaves the screen out. **Chat about this** hands the screen to aegentica.se only when tapped: the screen text, and a smaller copy of the screenshot (a JPEG of at most 1280 px on the long side and about 1.5 MB), become a new chat's draft there. They are held in the app's memory only until aegentica.se takes them, never put in the launch intent or written to the phone's storage by the app; the draft and its attachment then sit in the web view's storage like any draft, until sent or removed. Once sent, the screenshot and text are kept with that chat on the owner's server and go to the chat's model provider, as any attached picture does. A selection goes as text, never with a screenshot. With the screen left out, the button is not offered.

**Agent voice (optional, Nano build):** after a one-time consent and while a conversation runs, the microphone streams to the owner's LiveKit server and agent, and from there to the providers configured on that server. Matching note excerpts are sent when the agent searches. A shared screen is sent as video until sharing stops; Android asks for consent each time and stops sharing when the phone locks. The phone holds only an access code (Keystore) and receives 10-minute room tickets; the LiveKit secret and provider keys stay on the server. A foreground notification with *Stop & save* is shown throughout. Agent suggestions run only when tapped.

**aegentica.se (the app's home):** the home screen loads the owner's own Ægentica (`aegentica.se`, Dokploy on the owner's server) in an app web view, signed in with the owner's password; its sign-in cookie stays in that web view. Only `aegentica.se` and `www.aegentica.se` load there; every other link opens in the browser. Chats, memory and settings are stored on the owner's server, as from any browser. The app lends the site, over a message channel posted to that origin only: read aloud through the phone's installed offline voices (the reply's text stays on the phone), dictation through Android's on-device recogniser, the phone's voice picker and menu, text shared to Ægentica (it becomes a draft, never sent by itself), the screen from the assistant's *Chat about this* (text and a smaller screenshot, likewise only a draft), and the microphone for live voice, granted to those pages only after Android's own permission; camera, location and other permissions are refused. Nothing the site sends is run as a command.

**Glimt (browsing, from the Phone menu):** loads Glimt Web from the owner's server (`glimt-136-148-209-184.sslip.io`, Dokploy) in an app web view; only that address loads there, and every other link opens in the browser. Glimt keeps its OpenRouter key, chats and saved pages in that web view's private storage on the phone. Questions, with the page or shared text when it goes with the question, go to OpenRouter with the owner's key, as in Glimt Web. Opening a web address in Glimt sends the address to Glimt's server, which fetches the page (it accepts only requests carrying a working OpenRouter key, keeps only the key's hash for ten minutes, and reaches public addresses only). The app lends Glimt, over a message channel posted to Glimt's origin only: read aloud through the phone's installed offline voices, dictation through Android's on-device recogniser (Glimt's own microphone recording is not used, and the page gets no microphone or camera access), and OpenRouter sign-in in Chrome, finished through a callback on `localhost` that the app listens on for that one redirect. Shared text and links go to aegentica.se, not Glimt.

**Insights:** the note's text is sent to the same provider as Polish (Nano on device, or the connected provider). Suggestions are stored only after review, as ordinary records whose source names the provider and the note, and Undo removes them.

A connected routine requires separate consent to its prompt, provider and fixed source IDs. It can read those records' current contents at execution time; it does not fetch calendars, Beeper or other apps automatically. Prompt/provider changes invalidate approval. There is no automatic retry of uncertain requests, message sending or calendar mutation. Local Nano routines only notify the user to open the app.

The app implements no analytics, advertising SDK, remote content synchronization or account backend. System services still manage initial model/speech downloads. [ML Kit service constraints](https://developers.google.com/ml-kit/genai).

## Local storage

| Data | Storage | Removal and limits |
|---|---|---|
| Notes, tasks, people, projects, collections | App-private SQLite | No automatic recent-history eviction; Trash, restore and permanent deletion |
| Originals, links and typed fields | Same SQLite database | Original captured text survives edits; permanent item deletion cascades its links/values |
| Current chat and draft | SQLite content keys | Latest 40 completed exchanges; New archives current content |
| Recent conversation archives | SQLite content keys | Up to 12 within the existing soft size budget; delete individually or clear |
| Reader text and sentence position | SQLite content keys | Replaced by the next selected text; retained for explicit resume |
| Beeper snapshots and drafts | SQLite | Snapshots are ordinary deletable notes; drafts persist until successful send or replacement |
| Routine approvals and execution history | SQLite | Local approval gates; history does not execute commands; remote approvals are excluded from restore |
| Speech, privacy and calendar selection | Private preferences | Changed through settings; cleared with app data |
| Provider configuration/key | Separate private preferences; key encrypted using Android Keystore | Disconnect removes saved configuration; never included in manual content backups |
| Widget's last answer | Private preferences | Replaced by successful result or cleared by New |
| Manual backups | User-chosen document provider | Readable JSON; user controls destination, retention and any provider sync |
| Markdown vault (optional) | Folder chosen with Android's folder picker; persisted permission | Plain Markdown including verbatim originals, readable by any app or sync tool with access to that folder; Stop syncing releases the permission and leaves written files in place |
| Transcripts | App-private SQLite notes | Verbatim original plus working text; ordinary Trash/delete/export rules |
| Recorded and shared audio | App-private files (`files/audio`), listed per note | Excluded from Android backup, workspace exports and the Markdown vault; deleted with the note's permanent deletion; uninstall removes it. Export notes before uninstalling, and keep important recordings elsewhere too |

Chat preferences migrate once in a SQLite creation transaction. The old value remains a recovery copy until that key is next written or cleared, then is removed. Explicit clear cannot resurrect it through a repeated migration.

SQLite content and exports do not have a separate app encryption layer; Android supplies app isolation and device storage protection. Provider keys use Keystore-backed encryption. Uninstall/clear storage removes local data. Automatic backup and device transfer rules exclude preferences/files/databases; vendor behavior still needs device testing. Export before uninstalling to resolve a signing conflict.

Workspace imports merge transactionally. Conflicting content becomes separate records, including visible imported conversation/draft notes. Invalid imports roll back. Imported routines are paused; provider credentials, permission grants, calendar selection and remote approvals are not restored. Restored run history cannot replay an action.

## Permissions

- **Microphone:** requested when starting dictation, Voice or Transcribe, never merely to view setup. Dictation and Voice stop on navigation/pause. **Transcribe** is the one explicit exception: after you tap Start it runs a microphone foreground service with a persistent notification (Pause, Stop & save) so it continues while the phone is locked. It never starts from the background, at boot or after process death, and it pauses whenever another capture in the app needs the microphone. Notification and lock-screen text is generic; transcript text stays in the app.
- **Calendar read:** requested when choosing calendars. Queries include only selected IDs. Creating/opening events uses Android handoff UI; no direct calendar write permission.
- **Beeper read/send:** separate custom runtime grants. Read recent chats, then explicitly choose a conversation. Saving an excerpt for AI requires another visible choice. Send reviews exact destination and text.
- **Notifications:** requested when setting reminders. Denial retains the saved item and explains that it cannot alert. Reminder delivery uses generic text.
- **Foreground media service:** only explicit playback of existing text. It cannot run Nano in the background or reopen capture.
- **Internet/network state:** used by the optionally configured connected provider and network-constrained jobs. No provider request is sent without configuration/approval.
- **Boot completion:** restores pending reminders and scheduled job eligibility after restart.
- **SET_ALARM:** normal permission for user-requested Clock handoffs.

No broad storage, contacts, direct-call, location, notification-listener, accessibility-service or wake-word access is requested. Document import/export uses Android's file picker. Review merged dependency manifests when changing SDK versions.

## Visible surfaces

Widget previews default off; enabling them can show a pinned note or last answer to anyone viewing the home screen. Compact widgets omit content. Private screen protects app windows/Recents using FLAG_SECURE; it does not encrypt files or protect other apps opened through handoffs. Clipboard copies set sensitive-preview metadata. Editors request no personalized learning, but the keyboard controls its own behavior.

**Ægentica keys** is an input method, so Android warns when it is turned on that a keyboard can read what you type. It reads the focused field (or its selection) only when you tap an action, never in password fields, and sends that text only to the model chosen for the assistant (Gemini Nano, or the connected model after its opt-in). *Reply to copied* reads the clipboard only when tapped. Nothing is written to the field until you tap *Replace* or *Insert*, and nothing typed is stored.

Media metadata and reminder/result notification text are generic. The reader still displays the full chosen text inside the app. Headset disconnection and audio-focus loss pause playback. Streamed foreground answers stop on leaving their original screen; explicit reader playback has its own lifecycle.

## External actions

Clock, Calendar, Maps and Dialer receive only the fields the user explicitly submits. Beeper receives the exact reviewed room and text. Actions are native validated operations; model-generated text is never interpreted as a command. Remote generation only saves a result note and can notify; it never performs a send or calendar write.

## Reporting

Use redacted screenshots and synthetic examples. Never put private exports, provider keys or signing material into public GitHub issues. Copy app info copies an explicit local metadata allowlist (build/package/device/Android/language), without conversations, audio or telemetry. CI uses synthetic core fixtures; no production security certification or device acceptance is implied by a green build.
