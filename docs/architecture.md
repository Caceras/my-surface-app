# Architecture

Ægentica AI is one native Android app with two product flavors. The UI uses framework views and shared tokens; there is no WebView, Compose runtime, backend, or cloud-model routing layer.

## Responsibilities

| Source | Responsibility |
|---|---|
| `MainActivity.kt` | Typed chat, editable dictation, reply actions, Settings, share intake |
| `ConversationSheet.kt` | Searchable local archives, previews, resume and deletion |
| `VoiceActivity.kt` | Foreground listening/thinking/speaking flow, speech setup, continuous-session controls |
| `ProcessTextActivity.kt` | Text-selection prompt/result flow and editable-selection return values |
| `SurfaceBrain.kt` | Variant-independent task, status, streaming, result and cancellation contract |
| `nano/…/Brain.kt` | ML Kit Prompt client, readiness, system-instruction fallback, generation fencing |
| `core/…/Brain.kt` | Deterministic uppercase implementation for tests and scaffolding |
| `Voice.kt` | Recognition lifecycle, language downloads, installed offline TTS selection, audio focus |
| `NativeActions.kt` | Validated explicit Android Clock/Calendar/Maps/Dialer handoffs |
| `NativeShortcuts.kt` | Static navigation action IDs and user-requested pinning |
| `NativePrivacy.kt` | Screen protection, widget-preview preference and sensitive clipboard metadata |
| `AdaptiveFrame.kt` | Centered reading column capped at 720 dp |
| `SpeechControls.kt` | Foreground MediaSession and headphone-disconnect handling |
| `ReadingScrollView.kt` | Reader-controlled scrolling for voice and selection streams |
| `StreamUpdates.kt` | Immediate first paint, then coalesced text updates at a minimum 48 ms interval |
| `Chat.kt` | Current exchanges, draft, recent archives, backup validation |
| `ResultStore.kt` | Last successful result used by the widget |
| `Design.kt`, `Ui.kt`, `PresenceView.kt` | Shared view styling, insets/keyboard motion, state feedback |

All source paths above are relative to `app/src/main/java/com/caceras/surfacelab` unless a flavor is shown. Runtime dependencies are restricted to the Nano source set. Test dependencies are separate.

## Request lifecycle

1. The surface captures an explicit user request. Share intake stages a draft; text-selection presets run the chosen action.
2. The activity assigns a request ID. The Nano provider also advances a generation token and cancels its tracked future.
3. Nano checks feature readiness and whether separate system instructions are supported. Prompts include a bounded selection of conversation context.
4. Speech receives cumulative partial text immediately. UI rendering paints the first partial immediately, then coalesces bursts to avoid repeatedly rebuilding Markdown spans for every token.
5. A final successful response replaces pending visual updates and is stored. Failed or cancelled partials are not recorded as completed exchanges.
6. Stop, replacement, pause, and destruction invalidate callbacks and clear queued rendering work. Underlying system downloads may continue under Android's control.

The 48 ms coalescing window is an implementation bound, not a measured frame-rate or device-latency guarantee. Speech and final responses do not wait for that window.

## Voice lifecycle

`Ears` creates only an on-device recognizer. Terminal results/errors release it. A generation counter rejects late callbacks; separate setup clients have cancellation and a timeout.

`Mouth` queues text until TTS initializes, checks that the chosen voice is installed and does not require the network, splits speech below the engine's input limit, and requests transient assistant audio focus. Losing audio focus mutes the remaining answer and updates the UI; it does not immediately resume over another app. Utterance IDs fence callbacks from earlier answers.

Keep talking is foreground/session-only. The next listen is scheduled after completed playback, only while resumed and without a speech problem. Setup, leaving, cancellation and explicit quieting disable it. Re-entering an existing Voice activity with a setup intent must never start a recognizer.

## Storage

Preferences use the `surfacelab` file in app-private storage. Current turns, a draft, speech preferences, archived conversations, and the last widget result have separate keys.

Current chat retains the newest 40 exchanges. Archives retain at most 12 whole conversations under a soft 512,000-character serialized budget; the newest archive is retained in full even when larger. The first retained item is therefore an exception to the soft size budget. Old archives can be evicted by either limit. This is recent history, not unlimited storage.

Conversation exports use `surface-chat-v1`. Optional `conversations` extends the older single-chat format. Import/export use the same 4,000,000-byte UTF-8 limit; exports are validated before writing. The parser limits turn/archive counts. Restore replaces the active chat after confirmation and merges retained archives with deduplication.

## Android surface contracts

Main and Voice are wrapped in an AdaptiveFrame; dialogs use the same reading width. Voice identity/status live inside the scrollable content, leaving the foreground action/navigation controls reachable in short windows. Insets combine system bars, keyboard and display cutout. Default system back is explicitly enabled without an always-consuming app callback.

Static History/Actions shortcuts route through explicit action names to MainActivity. They open navigation without sending or replacing the draft. Pin requests use Android's ShortcutManager confirmation. Widget RemoteViews share production/test construction, switch between compact/full layouts using launcher options, and expose no last-answer text unless opted in. Widget broadcasts target a non-exported receiver.

Native actions are constructed from validated form values, not arbitrary strings interpreted as intents. Failed/missing handlers produce a visible recovery message. Leaving for a destination app invokes the same cancellation and draft-saving lifecycle as any other foreground exit.

## Extension points

Add a task to `Task`, its prompt definitions, and the relevant flavor aliases. Keep device-specific AI behavior behind `SurfaceBrain`. New surfaces should call the existing conversation/voice flow and define their foreground and permission behavior explicitly. `tools/scaffold.py` generates a smaller starter and is not a source of truth for the complete assistant.

## Shared interaction contracts

`Design.kt` owns sheet headers, native preference switches and styled private editors, alongside the palette/typography helpers. Settings, History and Actions call the same navigation pause path before opening. It cancels the stream and audio, retains the pending draft, hides the keyboard and rejects late results. Voice retains an explicit quiet flag through final output so a muted reply does not re-advertise an active quiet control.

The source-to-release boundary is described in [the iteration workflow](iteration-workflow.md). `release_evidence.py` reads actual JUnit/lint/APK outputs; `publish_preview.py` verifies provenance and downloaded bytes before updating aliases.

`AppInfo.kt` owns the explicit support-metadata allowlist. Settings copies it locally through `NativePrivacy.copy`; it never reads conversation preferences or sends telemetry. Native action form buttons and editor callbacks share one guarded validation/handoff path.
