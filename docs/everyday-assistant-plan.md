# Ægentica AI: everyday assistant plan

16 September 2026 · Implemented preview; see the delivery status below and [everyday guide](everyday-workspace.md). Builds on the native Android preview and [capability inventory](android-native-plan.md).

## Product promise

Capture a thought, find it again, and act on it—by typing or speaking, without deciding where it belongs first. Riki owns vision, priorities and taste; the technical collaborator owns implementation choices, data integrity, testing, documentation and release preparation. Present meaningful product tradeoffs, not a menu of frameworks.

Success is a reliable daily loop: dictate an idea, keep the original, turn part of it into a task, link it to a project, find it next week, and listen while walking. The same underlying records support a simple list today and a personal knowledge system later.

## Three destinations

- **Today:** a restrained agenda, due tasks and items the user has chosen to revisit. Useful empty state with Capture and AI; no decorative dashboard full of empty cards.
- **AI:** the existing assistant, now able to work with explicitly selected notes, projects and connected sources. Typing, dictation and playback share one thread and editable draft.
- **Library:** notes, people, projects and user-created collections. Search first. List and table are views of the same records; relations appear as useful links and backlinks before any optional graph visualization.

Capture is available everywhere through a shared composer and explicit **Save note / AI** action. Preserve the last intentional mode and show it clearly. Saving a note never depends on model availability or guesses about intent. Long-press microphone can be considered only as an optional shortcut after the visible tap controls work well.

## End-to-end example

“An idea for the Aegentica onboarding: explain it with three examples. Remind me Friday to draft them.”

1. Dictation appears live in an editable draft. Save immediately as a durable note, with original wording retained.
2. The assistant can propose a title, link to the Aegentica project and a reminder. Show the resolved date/time before scheduling; clarify ambiguity without blocking note saving.
3. Accepting creates linked records. Undo reverses the local changes. No parallel copies drift between chat, notes and a table.
4. On Friday the reminder opens that exact note. Ask “What was my plan?” and receive an answer with a link to the original.
5. Tap Listen and continue using the phone; tapping the microphone pauses playback before listening begins. The original text remains available throughout.

## Delivery order

| Release slice | User-visible result | Acceptance boundary |
|---|---|---|
| 1. Capture and recall | Durable typed/dictated notes; autosave; edit, search, pin, trash/undo; save an answer as a note; a compact useful widget | Process death and import/migration do not lose or duplicate notes; airplane-mode capture works; originals survive AI suggestions |
| 2. One text/audio experience | One conversation/draft across typing and speaking; common player with sentence highlighting, pause/resume, speed and playback position; note/chats read-aloud | Explicit existing-text playback survives screen lock through a supported service; interruptions stop safely; capture never restarts itself |
| 3. Today and reminders | Tasks linked to notes, selected-calendar agenda, reminder/date confirmation, actionable notifications | Denial/revocation, time zones, daylight saving, reboot and duplicate delivery covered; Android Calendar stays authoritative for connected events |
| 4. Connected conversations | Beeper availability/permission setup; selected chat retrieval, summaries linked to messages, editable reply drafts | Missing/unsupported provider degrades cleanly; no silent bulk import or background sending; exact recipient and text reviewed before Send |
| 5. Collections and relations | People/projects, custom typed fields, links, saved filters and table/list views | One record identity across views; rename/delete/restore preserve links; no inferred relationship represented as confirmed fact |
| 6. Scheduled assistance | Explicit routine scope, cadence, model and delivery; run history, pause/disable and retry limits | Separate reminders from AI execution; missed/failed runs are visible; remote processing requires opt-in and approved data scope |

All six areas now have an implemented preview path; the status section below distinguishes delivered behavior from longer-term intent. Stable signing and a tested export/restore path are prerequisites before trusting irreplaceable notes to repeated APK upgrades. Avoid calendar, messaging and automation permissions until their respective slices exist.

## Data foundation

Replace conversation preferences as the primary content store with a transactional local SQLite database. Preferences remain suitable for settings. The current 12-conversation archive policy must never become the retention policy for notes.

Use stable IDs and typed records: notes (body/revision), tasks (status/due time/time zone), people, projects, collections, and references to external events/messages. Shared metadata includes creation/update times, provenance and deletion state. A relationships table links records; custom property definitions have explicit types, and indexed values serve filtering/sorting. Conversation messages and attachments have their own lifecycle. Do not put every field into an unvalidated generic JSON blob or introduce a separate graph database.

Full-text search and explicit links come first. Add semantic retrieval only after a fixed set of real questions demonstrates a meaningful improvement. Vector indexes would be rebuildable derived data, never the authoritative memory. AI proposals retain source references and are distinguished from user-confirmed information. Users can inspect, edit or forget saved memories; deleting a record removes its derived search entries too.

Migrate existing chat/draft/archive data transactionally and idempotently, validate it, and keep a recoverable copy until acceptance. Continue accepting `surface-chat-v1`; introduce a versioned broader export with a manifest and attachment handling before richer data ships. Do not label a plain JSON export encrypted. Any future sync needs an outbox, conflict handling and an explicit privacy model; the first slice works without an account.

## Shared interaction and AI architecture

Keep the native screens and current design language. Introduce a shared session/controller beneath chat, dictation and playback so activity transitions do not own conflicting drafts or model requests. The content repository is independent from model providers and Android integrations. Every async result belongs to a request/session generation; cancellation and explicit navigation behavior remain enforced.

AI requests use an explicit context selection: current text, selected linked records and authorized external sources. Display source links and say when nothing relevant was found. Source text is untrusted data, not executable instructions. The model may propose typed operations; deterministic validation and an action dispatcher decide what can execute. External sends/changes require review; action IDs and a durable execution record prevent retry duplication. Local reversible changes support undo.

Nano remains the default for supported short local tasks. Evaluate specific transformations rather than assuming every feature needs a large conversational prompt. Optional connected intelligence can handle harder reasoning and unattended routines, with visible data scope and cost controls. No silent cloud fallback. Use a fixed, redacted English/Swedish evaluation set covering retrieval accuracy, transformations, ambiguous dates, refusal/error states and latency before changing the default model path.

The existing main/core dependency boundary is retained for the first SQLite/framework implementation. If a later scheduler or playback architecture benefits from an AndroidX library, document that dependency decision explicitly instead of quietly working around the repository rules. No web UI replacement or unrelated framework migration is necessary.

## Verified platform opportunities

**Beeper on the phone:** Beeper documents an experimental Android content provider supporting chats/messages/contacts, queries, insertion and runtime read/send permissions. Changes currently notify through the chats URI only. This makes a direct Pixel adapter worth testing before introducing a desktop relay. Start read-only with user-selected context, then separately authorize sending. Installed Beeper version/protocol behavior still needs device validation. [Beeper Android provider](https://developers.beeper.com/android/content-providers/).

**Better dictation:** Google's alpha GenAI Speech Recognition API offers Advanced mode on Pixel 10/11 and lists Swedish as beta. Evaluate it alongside the existing platform recognizer on Riki's actual device; preserve the proven path until quality, latency and availability justify promotion. Raw recording retention should be an explicit choice. [Google speech API](https://developers.google.com/ml-kit/genai/speech-recognition/android).

**Scheduled AI limitation:** ML Kit GenAI inference requires the top foreground application; a foreground service does not bypass the restriction. Thus local routines can schedule a reminder to open/generate, while unattended generation requires another explicitly enabled execution provider. Ordinary durable background work is not exact-time delivery. [ML Kit constraints](https://developers.google.com/ml-kit/genai) · [Android periodic work](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).

**Listen while doing other things:** explicit playback of already available text/audio needs a separate playback lifecycle and platform media controls; that does not authorize background Nano inference or continuous microphone capture. [Android background playback](https://developer.android.com/media/media3/session/background-playback).

**Calendar:** read selected calendars after permission; start event creation with the existing editable Android handoff. Handle external changes and permission revocation before direct writing. [Calendar provider](https://developer.android.com/identity/providers/calendar-provider).

## Native surfaces and taste

The owner's September 16 screenshots show the branding working on the actual Pixel, and a large widget with very little content. This is evidence of wasted space in that widget state, not certification of every launcher size. Give small sizes capture/search controls; give larger sizes useful user-selected agenda/pinned-note content. Keep private previews off until enabled.

Prioritize share-to-note, selected-text capture, adaptive widgets, capture/voice shortcuts, Quick Settings entry, notification actions, keyboard shortcuts, predictive back, accessible large text and media/headset controls. Reuse one capture/session path across these surfaces. No notification scraping, AccessibilityService automation or always-listening hotword is needed for the first everyday assistant.

Preserve generous typography and sky-blue restraint, but trade the static welcome area for useful content once the user has notes or tasks. Surface extra fields and relations only when requested. Start with a few useful record types, not a schema designer on first launch. A graph visualization is optional; reliable linked recall is the actual benefit.

## First-slice definition of done

Create and edit notes without AI or network; autosave survives recreation/process death; text and dictated input merge without overwriting a newer edit; pin/search/trash/undo work; saving an answer creates a source-linked note; exports round-trip existing chats plus notes; widget sizes waste no large empty region in the tested states. Inspect real native renders, run focused data/lifecycle regressions plus the full candidate gate, then publish an exact Nano build and record physical Pixel checks separately. Do not add a new regression solely for a copy or spacing edit.

The initial planning commit introduced no runtime changes. The following implementation now adds optional permissions, SQLite migration and the explicitly configured connected model client; it does not provision a hosted account or remote service.

## Implementation status

The current implementation introduces all six areas as one native workspace: notes/search/migration, sentence-based background reading, tasks/calendar/reminders, experimental Beeper, linked collections/typed fields, and local plus opt-in connected routines. The navigation destination is **AI**, as requested. Native checks and the published candidate record establish what has been verified; do not treat this section as physical-device approval.

The scope is deliberately explicit: existing hands-free Voice continues with Nano; the typed/dictated composer supports connected AI. No alpha speech SDK replacement was made without Pixel evaluation. Tables support typed values and membership, but not formulas, saved per-column filters or an arbitrary query builder. Search uses full-text indexing and selected context; there is no vector index or automatic memory extraction. Attachments, sync and graph visualization remain future extensions. External account/key setup, Beeper availability, device acceptance and signing continuity cannot be supplied by app source code.

See [everyday workspace](everyday-workspace.md) for actual controls, limits, privacy and recovery behavior. The original sections above explain intent; where behavior differs, the everyday guide describes this implementation.
