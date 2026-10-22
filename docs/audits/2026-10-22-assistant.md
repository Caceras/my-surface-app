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

- **Phone assistant:** `VoiceInteractionService` role, a session that hands screen text and screenshot to a glass assistant panel, and Nano image input.
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

The owner reviewed the first assistant render as "horrible" and pointed to their own glass ask bars (EmmaDiaz `AskBar`, awesome-SEO `AskBar`). The panel was rebuilt to match:
- a floating rounded glass prompt bar with an Æ context chip and a round send button;
- suggestions and the answer in glass cards above it, with text actions;
- a light bottom shade instead of a dark scrim.

Renders now show a stand-in app behind the panel instead of the test's empty background. Without a cross-window blur, the glass is nearly opaque so answers stay legible over busy screens.

Preflight caught two problems, both fixed:
- service classes must live in their own files;
- the assistant task's menu alias must be empty, with `Task.fromComponent` guarded against matching it.

## Acceptance

- Local gate and CI evidence are recorded in the PR.
- Physical-device checks are listed in [testing](../testing.md#assistant-providers-and-agent-acceptance) and remain open.
- The LiveKit server is not deployed; that needs the owner's accounts and approval ([agent voice](../agent.md)).
