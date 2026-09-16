# Your everyday workspace

Ægentica AI now has three destinations: **Today · AI · Library**. They use the same private local records and conversation. No account is required for notes, reminders, on-device AI or speech. Connected AI is optional and requires your own compatible provider account.

## Capture and remember

Open **Library → Capture a thought**, use **Capture note** from the launcher, or tap **Capture** on the widget. Type or dictate, edit the transcript, then Done. Changes autosave after a short pause and flush when leaving. A new note becomes a durable item as soon as it has content. The first finished version is retained as Original. Subsequent editing preserves that original; this is not unlimited revision history.

**AI → Save** turns the composer draft into a note without running a model. Each completed answer also has Save, with the question recorded as provenance. Share plain text into the app, then choose Save or Send. Nothing is sent merely because it was shared.

Library supports word search, type filters, pinning, Trash, restore and permanent deletion. Search uses local full-text indexing; it is not semantic/vector search. Lists show up to 200 results: narrow the search for older records. Notes are not subject to the recent-conversation retention limit. Export before uninstalling; local storage does not survive uninstall.

## Build your knowledge

Use New to create notes, tasks, people, projects, collections or routines. Link any saved item to another. Backlinks appear in both items. Long-press a linked item to remove the relationship. Trashing hides an item and its search entries; restoring preserves its relationships. Permanent deletion removes that item's links and property values, leaving other records intact.

A **collection** groups existing records. Use Link to add members and Add a field to define text, number, ISO date (`YYYY-MM-DD`) or checkbox (`true`/`false`) properties. Table view shows the same records; swipe horizontally for columns and tap a row to edit values. Values are validated transactionally. Values refresh immediately. Filter across rows/values and choose a typed sort column; each collection remembers that view. Collection membership and general relationships are distinct.

This first table implementation does not provide spreadsheet formulas, arbitrary joins, per-column saved filters or a visual graph. Stable identities and typed fields provide the foundation without creating duplicate copies of notes.

## AI with sources

Open a saved item and tap AI, or choose **AI Settings → AI sources**. Select up to five live records. The next request includes bounded excerpts, asks the model to treat them as reference data, and requests numbered source citations. You can inspect the selected records in Library. Citations are model-generated and must be checked; they are not a guarantee of factual grounding. Long sources are excerpted. There is no automatic scan of your whole phone or Library.

Typing and dictation use the same editable AI composer. New clears its selected sources. Existing hands-free Voice shares conversation history and selected source context, uses Nano, and retains its explicit foreground/Keep talking behavior. Connected AI applies to the typed/dictated AI composer; it does not silently change hands-free Voice or selection presets.

A routine opened through Run with AI stages its prompt for review. You still press Send. Successful routine answers are saved as linked notes, with run history; cancellation/failure remains visible.

## Listen anywhere

Choose **Listen** on a saved note or completed AI answer to open the reader. It has sentence highlighting, Previous/Next, Pause/Play and speed selection. Explicit playback uses a media playback service, allowing screen lock, headset controls and notification transport. Notification metadata is generic. Resuming restarts the current sentence, not the exact word. Position is retained on this phone; after process death, open the player and explicitly press Play.

Microphone capture pauses the reader. Headphone disconnection or audio-focus loss pauses playback. It never restarts the microphone itself. Live streamed replies in the existing hands-free mode remain foreground-only. Installed offline TTS voices are required; download them in Android speech settings. Real device audio routing and recognizer quality need Pixel validation.

## Today, tasks and calendar

Today gathers open tasks, selected-calendar events for the coming week, pinned items and AI routines. Create a task directly, or from a note with Create linked task. Choose a date/time, review the explicit time zone, then set the reminder. Notifications have Done and ten-minute snooze where applicable. Readable note text stays off the lock screen.

Reminders use inexact Android alarms: battery restrictions can delay them. Reboot, time changes and app updates reschedule pending records. Recurrences preserve the stored time zone/local time across daylight-saving changes and skip missed intervals. Force-stop prevents Android background work until the app is opened again. Notification permission is requested only when setting a reminder; denial preserves the task.

Choose calendars only when you want agenda access. The app reads only selected calendars in its query and refreshes when returning to Today. Event taps offer Open in Calendar, Save as note or Save & use with AI; new events remain editable Android handoffs through AI → Actions. Revoking Calendar permission disconnects the view. Ægentica does not copy your calendar into its canonical database or perform silent calendar writes.

## Beeper

Use **Today/Library → More → Beeper conversations**. A compatible installed Beeper content provider is required. Its Android API is experimental and needs actual-device validation. Reading and sending have separate runtime permissions.

Choose a chat to inspect up to 40 text messages. Use with AI asks before saving the displayed excerpt as a note; it records room/message identifiers and attaches that note as context. This is a deliberate saved snapshot, not live message synchronization. Deleted messages are excluded when returned by the provider.

Draft reply keeps editable text locally. Review shows the exact chat, network, room and message before Send. There is no background sending, model-generated command execution or automatic retry. If Beeper does not confirm acceptance, check the original conversation before trying again: an uncertain response is not proof the send failed. Drafts persist across reopening and are included in workspace backups. Provider errors do not disable notes or AI.

## Connected AI and routines

**AI Settings → Connected AI** accepts a full HTTPS Chat Completions-compatible endpoint, model ID and API key. Review the host, model and data scope before enabling. Changing the endpoint requires entering the key again. There is no built-in subscription, supplied credential, automatic model selection, browsing or silent cloud fallback. Provider compatibility and billing must be checked with your own account; a successful native build does not establish remote provider behavior.

Keys are encrypted using Android Keystore, outside the content database and excluded from exports/system backups. HTTPS is mandatory and redirects are refused. Requests are bounded in input/output size and duration. The provider sees the submitted question, bounded recent chat and selected sources. Requests use `messages`, `max_completion_tokens` and `stream: false`; providers must support those fields and plain text Chat Completions responses. Use provider-side spend limits; this app cannot infer prices or guarantee a monetary ceiling.

A local routine schedules a reminder to open Nano. For unattended generation, open a routine and **Set up connected routine**, separately review its prompt, named sources and configured host, and enable. Prompt/provider changes invalidate that approval. The approved source IDs are fixed; their current live contents are read at execution time. Link changes do not expand the approved scope. No other app is queried automatically.

Android JobScheduler runs connected routines when network/system conditions permit; scheduled times are not exact delivery guarantees. Each occurrence is durably claimed before networking and has no automatic retry. Failed/interrupted/uncertain runs remain in history. Results become linked notes with a generic notification. One-time routines disable after completion/failure; repeating routines advance to the next future occurrence. Disable a routine or disconnect the provider to stop future work. Already submitted requests cannot be recalled from a provider. Imported routines are paused and remote approvals are never restored.

## Backup and updates

**More → Export everything** creates a readable `aegentica-workspace-v1` JSON containing records, relationships, typed fields, content/drafts and execution history. Store it privately. Restore merges records transactionally; conflicting edited items get separate stable copies rather than overwriting local work. Malformed imports roll back. Conflicting imported conversation/draft content becomes visible Library notes; imported execution history is informational and never replays actions. API keys, permissions, remote routine approvals and calendar selections are not restored. The JSON export is not encrypted.

Legacy **AI Settings → Export/Restore conversation** remains `surface-chat-v1`, with the existing 40 current-exchange/12 archive bounds and 4 MB limit. Workspace backups have a 32 MB/10,000-record import bound; no attachments or raw recordings are supported. Initial SQLite migration copies the old conversation preferences once; subsequent writes retire the old value so cleared content does not reappear.

Use the exact build-specific Nano download in the handoff and verify its version in Settings. Private signing continuity still needs the documented owner-controlled secrets. If Android reports a signature conflict, export before uninstalling. No code change can recover the signing key of an already-installed ephemeral debug build.
