# Current preview changes

- Removed the chat welcome slogans, subtitle and oversized starter cards. Settings uses direct labels rather than promotional headings.
- Added single-asterisk/underscore emphasis, nested bold/italic, inline code and fenced code to the shared formatter. Speech and previews use the same plain-text interpretation.
- Replaced the fixed 48 ms streaming timer with display-frame coalescing, skipped duplicate provider chunks and retained the chat text buffer while updating its changed final lines.
- Reworked instruction-echo checking to index instruction fragments once and scan the growing answer, with exact verification of hash matches.
- Read-aloud now selects by installed offline voice quality instead of accepting the first matching voice. It checks voice-selection success, queues a bounded sentence look-ahead and avoids rewriting an entire note at every sentence. Long chunks prefer word boundaries.
- Voice initialization errors remain visible rather than presenting a Play action that silently does nothing. Cancellation, audio focus, headset controls and background playback remain in place.
- Asked the assistant to use plain language, short paragraphs and fewer decorative headings.

The existing Today, AI and Library workspace from PR #7 is now merged into main. Notes, conversations, relationships, tables, calendar/Beeper options, connected AI, routines and backups are retained.

This release improves the existing Android speech engine; it does not install a new neural voice or send text to a new cloud service. Actual voice naturalness and frame timing still require a Pixel check. No paid speech provider was added. Export your workspace before any uninstall; preview signing continuity is still not guaranteed.

[Repair details and acceptance boundaries](audits/2026-09-18-presentation.md).
