## Current polish pass

- Reduced oversized pills, fields, headers and chat chrome while preserving 48 dp touch targets.
- Simplified Today to Tasks, Calendar, Pinned and Routines; removed promotional filler copy.
- Tightened record cards, capture controls, the empty chat state and composer/navigation spacing.
- Settings now separates AI, Voice, Privacy, Android and Data more clearly.
- No storage, permission, model-provider or backup-format changes.

# Reader and streaming repair

- Replaced the slogan and oversized welcome cards with a plain question prompt and one writing shortcut. Settings labels and default AI instructions are more direct.
- Fixed single-asterisk italics, combined emphasis, inline/fenced code and links. Display and speech use the same parser. Incomplete emphasis no longer flashes its opening markers while streaming.
- Streamed replies now coalesce on display frames and update their existing text buffer instead of resetting the entire TextView. Reading earlier messages still disables following the answer.
- The saved-text reader and chat now choose installed offline voices by quality, with a saved voice preference. Open **Read aloud -> Voice** to hear samples and choose a voice; no API key or cloud service is added.
- The reader queues upcoming sentences rather than restarting speech after each sentence, and no longer rewrites the entire saved document for every playback update. Initialization failure remains visibly stopped.

The previous workspace PR has been merged into main. This is still a personal preview. Voice naturalness depends on installed voices, and actual Pixel audio/frame timing still needs device use; no human-sounding or frame-rate guarantee is claimed. Export your workspace before any uninstall because signing continuity is not yet provisioned.

## Cohesive workspace shell

- Today, Calendar, AI, Tasks and Notes now share one compact navigation model; workspace pages can be swiped horizontally as well as tapped.
- Calendar and Tasks are first-class pages instead of sections buried inside Today. Today becomes a concise overview.
- AI automatically receives a bounded private snapshot of open tasks and pinned workspace items, while explicit Sources still control deeper reference material.
- Android system bars now match the app surface instead of exposing a black navigation strip.
- Digital assistant setup now gives visible feedback and falls back to Android default-app settings when the assistant role picker is unavailable.
- Launcher and splash identity use the same sky-blue field and white Æ monogram.


### Polished motion and surfaces
- Workspace swipes now animate the outgoing and incoming pages together instead of replacing one page before the next appears.
- AI-to-workspace transitions use the same full-width horizontal motion so the app reads as one connected space.
- Cards, fields, pills and the composer use restrained translucent surfaces, softer outlines and consistent low elevation.
- Page/card spacing was normalized for a calmer rhythm without reducing touch targets.
- Android's reduced-motion preference still disables decorative movement.


- Unified mobile shell candidate retriggered for fresh Android validation.
