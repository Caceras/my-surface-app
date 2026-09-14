# Android surfaces

Every entry opens a foreground activity. The widget and tile do not run model inference in the background.

| Surface | Implementation | Behavior and limits |
|---|---|---|
| Typed chat | `MainActivity` | Launcher entry; restores current history and draft |
| Voice / assistant gesture | `VoiceActivity` with ASSIST and VOICE_COMMAND filters | Foreground voice; Android decides supported assistant selection/gesture behavior |
| Quick Settings tile | `SurfaceTileService` | Opens Voice using the platform's appropriate activity-launch API |
| Widget | `SurfaceWidgetProvider`, `widget.xml` | Last-result preview and separate Type/Talk actions; launcher controls pinning/placement |
| App shortcuts | Flavor-specific `shortcuts.xml` | Android launcher entries; API-qualified files expose voice where supported |
| Share target | MainActivity SEND filter, `text/plain` | Appends supplied text to a reviewed draft; does not fetch a shared URL |
| Text selection | Flavor aliases → `ProcessTextActivity` | Nano offers Ask/Summarise/Proofread/Make professional; core offers Uppercase |

Text-selection actions depend on the source app supporting Android's process-text contract. Editable preset results may replace the selection when appropriate; Ask results remain available for review. Surface receives the selected text, not the entire source app or page. Plain text is the only share MIME type currently registered.

## Add an entry

Use Settings to request the assistant role, widget pinning, or tile addition where the Android version supports it. Alternatively use Android's default-app settings, home-screen widget picker, and Quick Settings editor. Long-press the launcher icon for available shortcuts. The app cannot force acceptance or guarantee a launcher-specific gesture.

## Not implemented

Always-on hotwords, privileged lock-screen integration, arbitrary screen reading, notification replies/listeners, accessibility automation, Android Auto, Wear OS, Live Wallpaper, Now Playing, At a Glance and Quick Share integration are not part of this app. A phone's default-assistant selection does not automatically grant those capabilities.

## Development

Add an entry only with a defined user trigger, permission boundary, foreground lifecycle, and cancellation path. Reuse the shared chat/voice stores and immutable PendingIntents. Verify exported component behavior and test on the actual target launcher. The separate `tools/scaffold.py` generator can create smaller examples but does not generate the complete current assistant.
