# Phone assistant, OpenRouter, LiveKit and local models (research, 22 October 2026)

This is a research snapshot. Prices, model IDs and platform behaviour change quickly, so re-check the linked sources before acting. What shipped is described in [second brain](../second-brain.md) and [agent voice](../agent.md).

## Android: seeing and acting on the phone

- **Becoming the assistant.** A `VoiceInteractionService`, guarded by `BIND_VOICE_INTERACTION`, plus a session service. The owner picks it in Settings → Apps → Default apps → *Digital assistant app*; an app cannot request the role itself.
  - The `recognitionService` attribute is required but ignored since Android 12.
  - It is invoked with the corner swipe, and with the power button when that is set to *Digital assistant*.
  - There is no hotword for third-party apps.
  - Sources: [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [Android 17 CDD §3.8.4](https://source.android.com/docs/compatibility/17/android-17-cdd), [Pixel gestures](https://support.google.com/pixelphone/answer/7443425).
- **Screen context.** On invocation the app receives the screen's view text (`onHandleAssist`) and a screenshot (`onHandleScreenshot`).
  - Both respect the user's *Use text from screen* and *Use screenshot* switches.
  - Secure windows arrive empty.
  - **Apps targeting SDK 37 receive nothing** unless they declare `usesAssistData`, `usesAssistScreenshots` and `usesAssistStructureScreenContent` and hold `READ_ASSIST_STRUCTURE_SCREEN_CONTENT`. This app targets 36; the change is recorded in `res/xml/assistant.xml` for the SDK 37 upgrade.
  - Source: [VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession).
- **Acting in other apps.**
  - Only an `AccessibilityService` can tap or type in other apps. On a sideloaded APK it needs *Allow restricted settings*, triggers periodic privacy reminders, and is switched off by Advanced Protection.
  - Calling other apps' AppFunctions needs a Google allowlist. Gemini's own screen automation is not a public API.
  - Plain intents (timer, alarm, calendar, dial, maps, web search, share) need no special permission.
  - Decision: this app uses only explicit, tapped intents. Sources: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [restricted settings](https://support.google.com/android/answer/12623953).
- **Screen sharing (MediaProjection).** It needs consent for every session, a foreground service and a status-bar chip, and it stops when the phone locks. It suits an explicit "watch my screen" session. Source: [media projection](https://developer.android.com/media/grow/media-projection).

## OpenRouter

- **Basics.** An OpenAI-compatible endpoint at `https://openrouter.ai/api/v1/chat/completions` with a Bearer key.
  - SSE streaming skips `:` comment lines and ends with `[DONE]`.
  - Errors can arrive inside a 200 stream as a chunk with `error`.
  - Images go in as `image_url` data URIs. Audio goes in as `input_audio` (base64).
  - `GET /api/v1/models` is public and lists `architecture.input_modalities`, pricing and supported parameters.
  - Sources: [streaming](https://openrouter.ai/docs/api_reference/streaming), [errors](https://openrouter.ai/docs/api_reference/errors-and-debugging), [models](https://openrouter.ai/docs/api_reference/overview).
- **No real-time voice API.** Speech-to-text (`/api/v1/audio/transcriptions`) and text-to-speech (`/api/v1/audio/speech`) are separate request/response calls.
- **Privacy.** Prompts are not logged by default. `provider.data_collection: "deny"` excludes providers that store or train on data, and `provider.zdr: true` keeps requests on zero-retention endpoints. In-EU routing needs the Business plan. Source: [provider routing](https://openrouter.ai/docs/guides/routing/provider-selection).
- **Keys.** Keys can carry a spending cap that resets daily, weekly or monthly. A spent cap returns 402 or 403.
- **Models checked against the live list on 22 October 2026** (prices per million input/output tokens):

  | Model | Inputs | Price | Tools |
  |---|---|---|---|
  | `google/gemini-3.8-flash` | text, image, video, file, audio | $0.83 / $4.13 | yes |
  | `anthropic/claude-haiku-5.5` | text, image, file | $0.10 / $0.50 | yes |
  | `openai/gpt-6-luna` | text, image, file | $0.10 / $0.50 | yes |
  | `google/gemini-3.1-flash-lite` | text, image, video, file, audio | — | yes |
  | `deepseek/deepseek-v4.1-flash` | text, image | — | yes |

## LiveKit

- **Android SDK.** `io.livekit:livekit-android:2.29.1` works with framework Views (no Compose).
  - Its audio-routing library comes from JitPack.
  - It adds about 6.7 MB compressed per arm64 APK. It merges in a camera permission, which this app removes.
  - Screen share takes `ScreenCaptureParams`.
  - RPC payloads are limited to 15 KiB.
  - Transcripts arrive on the `lk.transcription` text stream; agent state is the `lk.agent.state` attribute.
  - Sources: [client-sdk-android](https://github.com/livekit/client-sdk-android), [RPC](https://docs.livekit.io/transport/data/rpc.md), [transcriptions](https://docs.livekit.io/agents/multimodality/text.md).
- **Agents.** `livekit-agents` 1.8.8 has built-in `openai.LLM.with_openrouter`. Realtime models (Gemini Live) accept live video. Neither turn detector supports Swedish, so turns end on silence. Sources: [OpenRouter in Agents](https://docs.livekit.io/agents/models/llm/openrouter.md), [turn detector](https://docs.livekit.io/agents/logic/turns/turn-detector.md).
- **Tokens.** Tokens must be minted on a server; use the standard token endpoint (POST, 201 with `server_url` and `participant_token`). Dispatch in a token applies only when the room is created, so use one room per conversation. Source: [token endpoint](https://docs.livekit.io/frontends/build/authentication/endpoint.md).
- **Cloud Build plan.** $0 with no card. It includes 5,000 participant-minutes, 1,000 cloud agent minutes and $2.50 of inference credits per month, all as hard caps. Source: [pricing](https://livekit.com/pricing).

## Local models

- **Gemini Nano** (ML Kit Prompt API `genai-prompt` 1.0.0-beta4).
  - Accepts text or **images plus text (up to 10 images)**, but no audio.
  - Input stays under about 4,000 tokens.
  - It runs only while the app is in the foreground.
  - Swedish quality is undocumented.
  - Source: [Prompt API](https://developers.google.com/ml-kit/genai/prompt/android).
- **Gemma 4 E2B via LiteRT-LM.**
  - Takes text, images and audio clips of up to 30 seconds, and supports function calling.
  - Apache-2.0; a 2.59 GB download with no login.
  - The larger E4B model has an open crash report on Tensor G5.
  - A candidate for a later on-phone model; not shipped yet because of the size and an alpha runtime.
  - Source: [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM).
- **Mac (24 GB).**
  - Ollama and LM Studio both serve an OpenAI-compatible `/v1/chat/completions` with images and tools.
  - Good fits: `gemma4:12b`, `qwen3.5:9b`, `qwen3-vl:8b`. Avoid `llama3.2-vision`, whose licence withholds multimodal rights in the EU.
  - Reach the Mac through Tailscale (free personal plan).
  - Ollama rejects unfamiliar hostnames behind `tailscale serve`, so bind it to the Mac's Tailscale address (`OLLAMA_HOST=<tailscale-ip>`) and use `http://<mac>.<tailnet>.ts.net:11434`. Tailscale encrypts that traffic end to end, which is why the app allows plain HTTP to `*.ts.net` only.
