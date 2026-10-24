# Android surfaces

> **Everyday workspace update:** [Current behavior and limits](everyday-workspace.md) covers Today · AI · Library, SQLite migration, notes/relations/tables, explicit background reading, calendar/Beeper access and optional connected AI/routines. Earlier foreground-only and preferences-only descriptions below apply to the original chat/Voice path unless updated here.

Every entry opens a foreground activity. The widget and tile do not run model inference in the background.

| Entry | Implementation | Behavior and limits |
|---|---|---|
| Home (Ægentica) | `AegenticaActivity` on `ShellActivity` (WebView) + `ShellBridge` | Launcher entry. Loads aegentica.se; only `aegentica.se` and `www.aegentica.se` load in the view, other links open in the browser. A message channel posted to the page's origin lends read aloud (`ReadingService`, installed offline voices, pause and resume), on-device dictation (`Ears`), the voice picker, shares, the assistant's screen with its screenshot, and the app's menu (the site's **Phone** row); the page may use the microphone for live voice after Android's permission |
| Glimt (browsing) | `GlimtActivity` on `ShellActivity` + `ShellBridge`, `LoopbackSignIn` | From the Phone menu. Loads Glimt Web from the owner's server; the same channel, plus OpenRouter sign-in in Chrome (localhost callback); no microphone for the page |
| Typed chat | `MainActivity` | The classic chat; reached from settings and shortcuts; restores current history and draft |
| Voice / assistant gesture | `VoiceActivity` with ASSIST and VOICE_COMMAND filters | Foreground voice; Android decides supported assistant selection/gesture behavior |
| Quick Settings tile | `SurfaceTileService` | Opens Voice using the platform's appropriate activity-launch API |
| Widget | `SurfaceWidgetProvider`, `widget.xml` | Optional last-answer preview, off by default; compact/full Type/Talk layouts; launcher controls placement |
| App shortcuts | Flavor-specific `shortcuts.xml` | Android launcher entries; API-qualified files expose voice where supported |
| Share target | `AegenticaActivity` SEND filter, `text/plain` | Shared text or a link becomes a new chat's draft on aegentica.se, never sent by itself |
| Ægentica keys | `AegenticaKeyboard` (input method) | Turned on in Android keyboard settings; the owner switches to it from Gboard. Reads the focused field only when an action is tapped, never in password fields; writes only on *Replace* or *Insert*. |
| Text selection | Flavor aliases → `AssistantActivity`, `ReadingActivity` and `ProcessTextActivity` | *Ægentica* opens the frosted sheet with the selection (Translate, Explain, Summarize, Draft a reply, Improve, and *Replace selection* in editable fields; Nano build). *Listen* reads the selection aloud (both builds). Nano also offers Summarise/Proofread/Make professional/Polish dictation; core offers Uppercase. |
| Transcribe | `TranscribeActivity` + `TranscriptionService` (microphone foreground service) | Started only from visible UI (Today, Notes, launcher shortcut, Quick Settings *Transcribe* tile via the visible screen); persistent notification with Pause and Stop & save |
| Live voice | `LiveActivity` (Nano) | AI page → Talk live with Gemini; foreground only, ends on leaving the screen |
| Digital assistant | `AssistantService` + `AssistantSessionService` → `AssistantActivity`, answered by `AgentSession` (`PhoneAgent.kt`) | Chosen in Default apps → Digital assistant app; corner swipe or power button in any app; screen text and screenshot only on invocation. Questions go to the phone agent on OpenRouter (signed in through Settings → Phone agent) with the screen attached; Gemini Nano answers only without a connection or a sign-in, labelled *Offline*. Opens listening when the microphone is allowed; hold the microphone to talk and let go to send; words sent while it works steer it. The sheet is opaque, keeps clear of the gesture bar and keyboard, and never shrinks while an answer streams. `AssistantActivity.withText(context, text, app, editable, handback)` opens the same sheet from inside the app with text as its context. *Chat about this* (before a question, or the pencil icon after an answer) opens a new chat on aegentica.se with the screen text and a smaller screenshot (JPEG, at most 1280 px and about 1.5 MB) in the message box, held in memory for that one start of `AegenticaActivity` and never put in the intent; nothing is sent until the owner sends it |
| Agent voice | `AgentActivity` + `AgentService` (Nano) | AI page → Talk with your agent; microphone foreground service with Stop; optional MediaProjection screen share |
| Audio share target | `AudioShareActivity`, SEND `audio/*` | Copies one shared audio file (≤300 MB, Gemini-supported formats) into a private recording note and opens it; uploads nothing |

Text-selection actions depend on the source app supporting Android's process-text contract. Editable preset results may replace the selection when appropriate; Ask results remain available for review. Ægentica AI receives the selected text, not the entire source app or page. Shared plain text goes to Glimt; shared audio becomes a recording note. No other share types are registered.

## Add an entry

Use Settings to request the assistant role, widget pinning, or tile addition where the Android version supports it. Alternatively use Android's default-app settings, home-screen widget picker, and Quick Settings editor. Long-press the launcher icon for available shortcuts. The app cannot force acceptance or guarantee a launcher-specific gesture.

## Not implemented

Always-on hotwords, privileged lock-screen integration, background screen reading (the screen is read only when the owner invokes the assistant or shares it), notification replies/listeners, accessibility automation (tapping or typing in other apps; Ægentica hands is the next build), Android Auto, Wear OS, Live Wallpaper, Now Playing, At a Glance and Quick Share integration are not part of this app. A phone's default-assistant selection does not automatically grant those capabilities.

## Development

Add an entry only with a defined user trigger, permission boundary, foreground lifecycle, and cancellation path. Reuse the shared chat/voice stores and immutable PendingIntents. Verify exported component behavior and test on the actual target launcher. The separate `tools/scaffold.py` generator can create smaller examples but does not generate the complete current assistant.

## New native access

- **Actions:** Clock timer/alarm, Calendar event draft, Maps place search and Dialer. These are explicit user-driven handoffs, not AI-executed commands.
- **Launcher:** Transcribe, Voice (API 31+), History, Capture note; shortcuts route through the home shell to their destinations. Settings can request pinned Chat/Voice shortcuts.
- **Widget:** compact/full RemoteViews, Ægentica AI identity, generic default text, optional last-answer preview, event-driven updates and resize handling.
- **Keyboard:** Ctrl+Enter, Ctrl+N, Ctrl+L, Ctrl+Shift+M; listed in the system shortcut helper.
- **Media buttons:** foreground Pause/Stop and headphone-disconnect handling; no background playback.
- **Windows:** capped reading width, scrollable voice content, cutout handling and default predictive system back. Physical folding/desktop/gesture behavior remains a device gate.

See the [complete Android plan](android-native-plan.md) for unsupported and conditional integrations, and [native actions](native-actions.md) for exact side effects.

## Workspace additions

Today and Library are internal native activities reached from the common Today · AI · Library navigation. Capture note launcher and widget actions route to the same editor. AI still receives plain-text sharing, with an explicit Save option before sending. The widget uses compact capture/talk controls and a bounded expanded preview; pinned-note text is included only when previews are enabled.

Calendar is read only after choosing calendars; Beeper is a separately permission-gated experimental provider. Reminder notifications open their exact record and provide supported actions. Explicit read-aloud has a media playback notification and lock-screen transport. Connected routine completion opens its result note. No overlay permission, notification scraping, accessibility automation, wearable or car surface was introduced. Background microphone use is limited to an explicitly started Transcribe session or Agent voice conversation, each with its notification; see [second brain](second-brain.md) and [agent voice](agent.md).
