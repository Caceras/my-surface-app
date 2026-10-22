# Assistant, providers and agent voice audit — 2026-10-22

## Problem

The owner asked for an AI assistant that can interact with the phone, with real-time voice and multimodal input through OpenRouter, LiveKit and local models. The owner's reference was Gemini's overlay over Google Sheets ("Share screen with Live").

Build 183 had these gaps:
- **No screen access:** the app answered the generic assist intent but could not see the screen.
- **One provider key:** connected AI stored a single key, so switching chat away from Gemini would break Live voice and Transcribe.
- **No streaming:** answers did not stream, so connected voice could not start speaking early.
- **Nano-only voice.**
- **No local-server path:** cleartext traffic was blocked everywhere.
- **No LiveKit path at all.**

## Repair

- **Phone assistant:** `VoiceInteractionService` role, a session that hands screen text and screenshot to an ask-bar panel, and Nano image input.
- **Assistant safety:**
  - screen content is marked as data;
  - one tap leaves the screen out;
  - the panel cancels on leave and keeps the screen in memory only;
  - follow-ups are tapped intents.
- **Providers:** per-host keys, presets for Gemini, OpenRouter (model picker, privacy routing) and local servers (Tailscale `*.ts.net` HTTP only), streaming SSE, image input, plain error messages, and voice and assistant use behind their own opt-in.
- **Live voice:** Gemini Live can start with the shared screen (text in the instructions, one JPEG frame).
- **Agent voice:**
  - LiveKit Android 2.29.1 (Nano, arm64, camera permission removed, JitPack limited to one group) behind a framework seam;
  - a foreground service with Stop;
  - read-only note tools and validated proposals over RPC;
  - screen share;
  - the server in `agent/` (LiveKit Agents 1.8.8, OpenRouter or Gemini Live, token endpoint with access code and 10-minute tickets) with its own CI job.

## Review findings fixed before push

The owner reviewed the assistant renders twice as "horrible" and pointed to their own ask bars (EmmaDiaz `AskBar`, then specifically the awesome-SEO `AskBar` in `apps/web/src/app/ask/AskBar.tsx`). The panel now follows the awesome-SEO bar's own tokens and measurements:
- square "strong glass" panels: a near-white fill, a hairline ink ring and a soft wide shadow;
- a neutral ink palette (`ask_*` colours, with dark-mode equivalents) instead of the app's sky-blue accents;
- a 48dp bar with a grey context chip (the Æ mark on an ink square, then the app name), a 16sp prompt, a microphone and a 36dp round send button (ink with a white arrow when there is text, grey when empty, a stop square while answering);
- a suggestions panel ("Ask about Sheets") and a conversation panel with an "Asked from Sheets" header, hide and clear buttons, the question as a grey bubble on the right, the answer at 14sp and bordered *Copy* and *Save to notes* buttons followed by plain text actions;
- no shade over the other app, and at most 680dp wide on large screens.

Renders show a stand-in Sheets screen behind the panel. Android cannot blur another app behind a single view, so the glass is slightly more opaque than the web bar to keep answers legible over busy screens. Compact controls draw at the web's 32dp but keep 48dp touch areas.

Preflight caught two problems, both fixed:
- service classes must live in their own files;
- the assistant task's menu alias must be empty, with `Task.fromComponent` guarded against matching it.

## Acceptance

- Local gate and CI evidence are recorded in the PR.
- Physical-device checks are listed in [testing](../testing.md#assistant-providers-and-agent-acceptance) and remain open.
- The LiveKit server is not deployed; that needs the owner's accounts and approval ([agent voice](../agent.md)).
