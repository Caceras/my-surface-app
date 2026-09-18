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
