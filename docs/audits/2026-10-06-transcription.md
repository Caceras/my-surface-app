# Transcription-first audit — 2026-10-06

## Problem

The owner asked for one native app that is "a transcription and note-taking app first and foremost", keeps the first input verbatim, adds AI polish only where useful, can use Gemini, and works as a window into a personal knowledge base. Build 3997843 (main) offered single-utterance dictation only: each pause ended capture, nothing continued with the screen locked, the "original" of a note was the first *finished* version (including typing), and AI questions saw only selected sources plus tasks/pinned items. Source review also found two flow defects introduced by the persistent shell: launcher *Capture note* and *History* shortcuts targeted `HomeActivity`, which ignored their actions, and Tasks → *Add task* passed an extra that `WorkspaceActivity` never read.

## Repair

- Explicit **Transcribe** (screen + microphone foreground service) with per-segment durable, time-stamped verbatim lines, continuous restart, bounded failure handling, stop-time flush and yielding to other captures.
- Engine abstraction: Android's on-device recogniser everywhere; ML Kit GenAI Speech Recognition (Advanced, `sv-SE`) preferred on Nano with fallback.
- **Polish** task (Nano/connected) over a reviewed copy; **Verbatim** view with *Use as text*.
- **Recall** of matching notes into AI prompts with visible titles; connected opt-in.
- *Use Google Gemini* connected preset; task-specific system prompts and a reasoning-aware completion budget.
- **Markdown vault** export with hashes and owned-file deletion only.
- Shortcut routing in the home shell; Add task opens the task editor; Transcribe replaces the redundant *Open chat* launcher shortcut.

## Acceptance

Local pinned toolchain (JDK 21, Gradle 9.7.1, SDK 36): `python tools/check.py`, then `gradle testCoreDebugUnitTest lintCoreDebug lintNanoDebug assembleCoreDebug assembleNanoDebug` — 197 tests, 0 failures, lint 0 errors in both flavors, both APKs built. CI evidence, published build and visual review are recorded in the PR. Physical Pixel checks are listed in [testing](../testing.md#transcription-acceptance); they remain open. New lint warnings are in existing tracked categories (translation strings, AndroidX suggestions).

## Second batch: live voice, insights, diff, tile

Build 176 shipped the first batch (PR #16, publication verified: unique and rolling Nano assets byte-identical, SHA-256 `6c1229…`, package/version 176). The owner asked for more depth. Added: Gemini Live voice with read-only note tools and resumption (Nano; OkHttp 5.4.0 because 5.5.0 requires compileSdk 37), reviewed Insights with provenance and undo, a verbatim word diff, and a Transcribe Quick Settings tile. Fixed release notes to use absolute links (the release body embeds this file). Local gate: 206 tests, 0 lint errors in both flavors, both APKs. Signing secrets remain unprovisioned: creating them from the agent session was blocked by the environment's secret-store policy and is left as an owner decision.

## Third batch: audio as the original, speakers

Build 180 shipped the second batch (PR #17; unique and rolling Nano assets identical, SHA-256 `ce1c9d7f…`, version 180). The owner asked for another round. Gap: transcripts kept only recognised text, so a better model could never re-transcribe, and nothing labelled speakers in Swedish meetings (Pixel Recorder labels US English only). Added: *Record audio* mode (ADTS AAC parts of 25 minutes under Google's 30-minute diarization limit, recovery after process death), playback, Gemini Transcribe (`gemini-3.5-transcribe`, `store: false`, uploads deleted, word times, foreground-only), Name speakers, and an `audio/*` share target. Review fixes before push: a recorder that cannot pause is now stopped (a pause must release the microphone), shared file names cannot escape the audio folder, transcription cancels on leaving the note like Polish, audio keys are left out of backups, and the next step (Transcribe, then Name speakers) is the primary audio action. Visual review of fresh renders moved *Name speakers* out of the scrolled tools row and aligned the Transcribe controls.

