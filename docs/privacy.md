# Privacy and data

This describes the implementation, not a guarantee about Android, keyboards, document providers, Google, Beeper or a configured AI provider. See the [everyday guide](everyday-workspace.md) for the controls that select these boundaries.

## Processing choices

Gemini Nano through AICore is the default. Hands-free Voice and text-selection presets use Nano. Dictation uses Android's on-device recognizer; **Transcribe** on the Nano build may use Google's on-device GenAI Speech Recognition (alpha, ML Kit) and otherwise Android's on-device recognizer. Spoken output selects installed voices that do not require a network. There is no silent cloud recognition or inference fallback. The app does not retain raw microphone recordings; transcripts store recognised text with time stamps.

**Connected AI is optional.** The user supplies an HTTPS endpoint, model ID and key (the *Use Google Gemini* preset only fills Google's endpoint and model), reviews the provider and enables it for the typed/dictated AI composer and Polish. That provider receives the submitted question or text being polished, bounded recent conversation and selected source excerpts, plus matching Library excerpts only if *Include matching Library excerpts* is enabled. For Gemini users in the EEA, Google's API terms apply paid-service data handling (prompts are not used to improve products but are logged for a limited period for abuse monitoring); Google's terms describe the API as not for consumer use, and offering it to other EEA users requires a billed project. Provider charges and retention rules apply. Redirects are refused and cleartext network traffic is disabled. Cancelled requests may already have reached the provider and cannot be recalled.

**Live voice (optional, Nano build):** after a one-time consent and only while the Live screen is open, microphone audio streams over TLS to Google's Gemini Live API with the user's Gemini key. When Gemini calls the read-only note tools, the matching titles, dates and excerpts (or one note's text, up to 4,000 characters) are sent as tool results. Leaving the screen, audio-focus loss or End stops capture; nothing runs in the background. The conversation transcript is saved locally as a note.

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

Media metadata and reminder/result notification text are generic. The reader still displays the full chosen text inside the app. Headset disconnection and audio-focus loss pause playback. Streamed foreground answers stop on leaving their original screen; explicit reader playback has its own lifecycle.

## External actions

Clock, Calendar, Maps and Dialer receive only the fields the user explicitly submits. Beeper receives the exact reviewed room and text. Actions are native validated operations; model-generated text is never interpreted as a command. Remote generation only saves a result note and can notify; it never performs a send or calendar write.

## Reporting

Use redacted screenshots and synthetic examples. Never put private exports, provider keys or signing material into public GitHub issues. Copy app info copies an explicit local metadata allowlist (build/package/device/Android/language), without conversations, audio or telemetry. CI uses synthetic core fixtures; no production security certification or device acceptance is implied by a green build.
