# Android surfaces

> **Everyday workspace update:** [Current behavior and limits](everyday-workspace.md) covers Today · AI · Library, SQLite migration, notes/relations/tables, explicit background reading, calendar/Beeper access and optional connected AI/routines. Earlier foreground-only and preferences-only descriptions below apply to the original chat/Voice path unless updated here.

Every entry opens a foreground activity. The widget and tile do not run model inference in the background.

| Entry | Implementation | Behavior and limits |
|---|---|---|
| Typed chat | `MainActivity` | Launcher entry; restores current history and draft |
| Voice / assistant gesture | `VoiceActivity` with ASSIST and VOICE_COMMAND filters | Foreground voice; Android decides supported assistant selection/gesture behavior |
| Quick Settings tile | `SurfaceTileService` | Opens Voice using the platform's appropriate activity-launch API |
| Widget | `SurfaceWidgetProvider`, `widget.xml` | Optional last-answer preview, off by default; compact/full Type/Talk layouts; launcher controls placement |
| App shortcuts | Flavor-specific `shortcuts.xml` | Android launcher entries; API-qualified files expose voice where supported |
| Share target | MainActivity SEND filter, `text/plain` | Appends supplied text to a reviewed draft; does not fetch a shared URL |
| Text selection | Flavor aliases → `ProcessTextActivity` | Nano offers Ask/Summarise/Proofread/Make professional; core offers Uppercase |

Text-selection actions depend on the source app supporting Android's process-text contract. Editable preset results may replace the selection when appropriate; Ask results remain available for review. Ægentica AI receives the selected text, not the entire source app or page. Plain text is the only share MIME type currently registered.

## Add an entry

Use Settings to request the assistant role, widget pinning, or tile addition where the Android version supports it. Alternatively use Android's default-app settings, home-screen widget picker, and Quick Settings editor. Long-press the launcher icon for available shortcuts. The app cannot force acceptance or guarantee a launcher-specific gesture.

## Not implemented

Always-on hotwords, privileged lock-screen integration, arbitrary screen reading, notification replies/listeners, accessibility automation, Android Auto, Wear OS, Live Wallpaper, Now Playing, At a Glance and Quick Share integration are not part of this app. A phone's default-assistant selection does not automatically grant those capabilities.

## Development

Add an entry only with a defined user trigger, permission boundary, foreground lifecycle, and cancellation path. Reuse the shared chat/voice stores and immutable PendingIntents. Verify exported component behavior and test on the actual target launcher. The separate `tools/scaffold.py` generator can create smaller examples but does not generate the complete current assistant.

## New native access

- **Actions:** Clock timer/alarm, Calendar event draft, Maps place search and Dialer. These are explicit user-driven handoffs, not AI-executed commands.
- **Launcher:** Chat, Voice (API 31+), History, Actions; Settings can request pinned Chat/Voice shortcuts.
- **Widget:** compact/full RemoteViews, Ægentica AI identity, generic default text, optional last-answer preview, event-driven updates and resize handling.
- **Keyboard:** Ctrl+Enter, Ctrl+N, Ctrl+L, Ctrl+Shift+M; listed in the system shortcut helper.
- **Media buttons:** foreground Pause/Stop and headphone-disconnect handling; no background playback.
- **Windows:** capped reading width, scrollable voice content, cutout handling and default predictive system back. Physical folding/desktop/gesture behavior remains a device gate.

See the [complete Android plan](android-native-plan.md) for unsupported and conditional integrations, and [native actions](native-actions.md) for exact side effects.

## Workspace additions

Today and Library are internal native activities reached from the common Today · AI · Library navigation. Capture note launcher and widget actions route to the same editor. AI still receives plain-text sharing, with an explicit Save option before sending. The widget uses compact capture/talk controls and a bounded expanded preview; pinned-note text is included only when previews are enabled.

Calendar is read only after choosing calendars; Beeper is a separately permission-gated experimental provider. Reminder notifications open their exact record and provide supported actions. Explicit read-aloud has a media playback notification and lock-screen transport. Connected routine completion opens its result note. No overlay permission, background microphone, notification scraping, accessibility automation, wearable or car surface was introduced.
