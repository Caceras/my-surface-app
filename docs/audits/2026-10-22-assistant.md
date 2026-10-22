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

- **Phone assistant:** `VoiceInteractionService` role, a session that hands screen text and screenshot to a frosted assistant sheet, and Nano image input.
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

The owner rejected three assistant designs: the first render, a rounded glass card, and a copy of the awesome-SEO `AskBar` (square, near-opaque white panels). Their direction: "transparent", "premium", "tasteful and minimal". Build 187 shipped the awesome-SEO copy; the follow-up replaces it with one frosted sheet:
- **Real blur.** The assistant is now a floating translucent window (`AssistantSheet` theme) sized to its content. Android's cross-window blur (`setBackgroundBlurRadius`, Android 12+) then blurs only the app behind the sheet. A full-screen overlay could not do this: blur covers a whole window.
- **Blur off.** When the system turns blur off (battery saver, or the developer setting), a listener switches the sheet to a nearly opaque tint so text stays readable.
- **One rounded sheet** (28dp corners, a hairline edge, 10dp from the screen edges) holding everything:
  - before the first question, a row of quiet suggestion chips;
  - after asking, the question in small grey type with a clear button, the answer at 15sp, the model name, and icon actions (copy, save, share, read aloud, talk live);
  - links and phone numbers as chips;
  - the input row: the Æ chip with the app's name, the prompt, a microphone and a round send button.
- **Colours.** Neutral ink `ask_*` tokens with dark-mode values; the dark sheet is a smoky glass.
- **Dismissal.** Tapping outside the sheet closes it.

Renders draw the sheet where Android places it, over a stand-in Sheets screen whose rows run under it, in light and dark. The blur in renders is simulated; real blur strength and frame timing need the Pixel.

Preflight caught two problems, both fixed:
- service classes must live in their own files;
- the assistant task's menu alias must be empty, with `Task.fromComponent` guarded against matching it.

## Acceptance

- Local gate and CI evidence are recorded in the PR.
- Physical-device checks are listed in [testing](../testing.md#assistant-providers-and-agent-acceptance) and remain open.
- The LiveKit server is not deployed; that needs the owner's accounts and approval ([agent voice](../agent.md)).
