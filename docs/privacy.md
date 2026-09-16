# Privacy and data

This describes the implementation, not a guarantee about Android, keyboards, document providers, Google, Beeper or a configured AI provider. See the [everyday guide](everyday-workspace.md) for the controls that select these boundaries.

## Processing choices

Gemini Nano through AICore is the default. Hands-free Voice and text-selection presets use Nano. Dictation uses Android's on-device recognizer; spoken output selects installed voices that do not require a network. There is no silent cloud recognition or inference fallback. The app does not retain raw microphone recordings.

**Connected AI is optional.** The user supplies an HTTPS endpoint, model ID and key, reviews the provider and enables it for the typed/dictated AI composer. That provider receives the submitted question, bounded recent conversation and selected source excerpts. Provider charges and retention rules apply. Redirects are refused and cleartext network traffic is disabled. Cancelled requests may already have reached the provider and cannot be recalled.

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

Chat preferences migrate once in a SQLite creation transaction. The old value remains a recovery copy until that key is next written or cleared, then is removed. Explicit clear cannot resurrect it through a repeated migration.

SQLite content and exports do not have a separate app encryption layer; Android supplies app isolation and device storage protection. Provider keys use Keystore-backed encryption. Uninstall/clear storage removes local data. Automatic backup and device transfer rules exclude preferences/files/databases; vendor behavior still needs device testing. Export before uninstalling to resolve a signing conflict.

Workspace imports merge transactionally. Conflicting content becomes separate records, including visible imported conversation/draft notes. Invalid imports roll back. Imported routines are paused; provider credentials, permission grants, calendar selection and remote approvals are not restored. Restored run history cannot replay an action.

## Permissions

- **Microphone:** requested when starting dictation/Voice, never merely to view setup. Capture stops on navigation/pause; no background microphone service.
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
