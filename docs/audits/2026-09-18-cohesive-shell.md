# Cohesive shell audit — 2026-09-18

## Problem

Build 151 had a coherent color palette but still behaved like separate apps: Today/Library lived in one activity, AI in another, Calendar and Tasks were buried sections, navigation replaced activities rather than feeling spatial, system navigation could show black, several settings actions looked like plain text, and assistant-role failure produced no useful feedback. Launcher/splash branding also used a dark mark that did not match the requested sky-blue/white identity.

## Repair

- Five destinations: Today · Calendar · AI · Tasks · Notes.
- Workspace pages switch in-place and support deliberate horizontal flings; AI remains the central destination and existing foreground cancellation/storage contracts remain unchanged.
- Today is an overview, Calendar and Tasks have dedicated content, Notes retains Library search/filter/storage behavior.
- AI receives only a bounded automatic snapshot of open tasks and pinned records; explicit selected Sources still provide full reference text. This keeps AI intertwined without silently dumping the whole database into every prompt.
- Status/navigation bars are painted with the app background and contrast enforcement is disabled where supported.
- Digital-assistant setup reports already-selected state, opens the role request when available, and otherwise opens the best available Android settings page with a visible instruction.
- Adaptive launcher and Android 12+ splash share the sky-blue field and white Æ mark.

## Acceptance

Run preflight and full Android quality, inspect fresh Today/Calendar/Tasks/Notes/AI/settings captures including night/large-font, verify both APKs/signatures, then publish a build-specific Nano APK. Physical swipe feel, OEM assistant-role availability, and launcher masking remain device checks.
