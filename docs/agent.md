# Agent voice: your own LiveKit agent

**Agent voice** (AI page → *Talk with your agent · LiveKit*) is a real-time voice conversation with an agent you host. It hears you, answers in Swedish or English, can search your notes, and can see a screen you choose to share. It also suggests actions such as a timer, alarm, calendar event, place, call, web search or saved note. Every suggestion appears on the phone as a button, and nothing happens until you tap it.

Use it when you want any OpenRouter model, server-side speech, or screen sharing while you use other apps. *Talk live with Gemini* needs no server at all, and stays the simplest option.

## What runs where

| Part | Where | What it does |
|---|---|---|
| Phone (Nano build) | Your Pixel | Joins a LiveKit room, publishes the microphone, plays the agent, shows transcripts, answers the agent's note tools and shows its suggestions. The conversation runs in a visible foreground service (notification with *Stop & save*) so it continues while you share another app's screen. |
| LiveKit | Your own `livekit-server` (already running at aegentica.se), or LiveKit Cloud (free *Build* plan) | Real-time audio and video between phone and agent (WebRTC). |
| Agent | `agent/` in this repository, as a Docker container | LiveKit Agents 1.8.8: speech-to-text, an OpenRouter model, text-to-speech; or Gemini Live in `realtime` mode. |
| Token endpoint | Same image, second container | Checks your access code and returns a 10-minute ticket for a fresh room that dispatches the agent. The LiveKit secret stays here; the phone never has it. |

## How it runs for you

You already host LiveKit at `wss://aegentica.se/livekit` (Dokploy → *agents* → *Ægentica LiveKit*), so Agent voice uses it instead of LiveKit Cloud:

- **The app:** Dokploy → *agents* → *Ægentica agent voice*, built from [`agent/compose.dokploy.yaml`](../agent/compose.dokploy.yaml).
- **LiveKit's key:** the agent reads it from LiveKit's own config file, mounted read-only (`LIVEKIT_KEYS_FILE`). The key is never copied into another file, app or chat.
- **Connections:** the agent reaches LiveKit over Dokploy's private network. The phone gets the public address (`LIVEKIT_PUBLIC_URL`).
- **Token endpoint:** `https://aegentica.se/voice-agent/token`. It shares the site's existing certificate, so no DNS change is needed.
- **The app's Environment holds two values:**
  - `OPENROUTER_API_KEY`: a key with a monthly credit limit.
  - `AEGENTICA_ACCESS_CODE`: the code the phone sends.
- **On the phone:** AI page → *Talk with your agent* → *Agent server settings*. Enter `https://aegentica.se/voice-agent/token` and the access code.

## Costs (October 2026; check before relying on them)

- **LiveKit Cloud Build plan:** $0 and no credit card. It includes 5,000 participant-minutes a month. A self-hosted agent counts as a participant, so that is about 2,500 conversation minutes. The limits are hard caps: no surprise bills.
- **OpenRouter (pipeline mode):**
  - Speech-to-text with `openai/whisper-large-v3`: about $0.03 per hour.
  - The model, for example `google/gemini-3.8-flash`: $0.83 per million input tokens and $4.13 per million output tokens.
  - Text-to-speech with `microsoft/mai-voice-2.1-flash` (Swedish voices `sv-SE-Harper` and `sv-SE-Grant`): $15 per million characters.
  - Give the key a credit limit at openrouter.ai/settings/keys.
- **Gemini (realtime mode):** about $0.005 per minute you speak and $0.018 per minute Gemini speaks.
- **Server:** your existing VPS is enough. The agent connects out to LiveKit and needs no inbound port; only the token endpoint needs an HTTPS address.

## Set up

1. **LiveKit:** create a free project at cloud.livekit.io and copy the project URL (`wss://…`), API key and API secret.
2. **OpenRouter:** create a key with a monthly credit limit.
3. **Access code:** generate one with `python3 -c "import secrets; print(secrets.token_urlsafe(24))"`.
4. **Deploy:** copy `agent/.env.example` to `.env` next to `agent/compose.yaml`, fill it in, and run both services (Docker Compose, or a Dokploy *Compose* app pointing at the `agent/` folder of this repository).
5. **HTTPS address:** route an HTTPS domain, for example `agent.your-domain.se`, to the `token` service on port 8080. In Dokploy that is a domain on the compose service.
6. **Phone:** open AI page → *Talk with your agent* → *Agent server settings*. Enter `https://agent.your-domain.se/token` and the access code, then tap *Start talking*.

Modes:
- **`AEGENTICA_MODE=pipeline`** (default) works with any OpenRouter model. It looks at a shared screen once per question, by attaching the newest frame.
- **`AEGENTICA_MODE=realtime`** uses Gemini Live with `GOOGLE_API_KEY`. It is the most natural voice and watches a shared screen continuously.

## Privacy

While a conversation runs, these leave the phone:
- your voice, to LiveKit and the agent, and from there to the speech and AI providers in `.env`;
- matching note titles and excerpts, when the agent searches your notes;
- the screen, as video, while you share it. Android asks for consent each time, shows a status-bar chip, and stops sharing when the phone locks.

OpenRouter requests exclude providers that store or train on prompts (`data_collection: deny`). Rooms are deleted when the conversation ends. The final words are saved verbatim as a note on the phone. The LiveKit secret and the provider keys stay on the server.

## Verified versus still to check

Automated tests run against a fake room and a fake token server:
- **Phone side (`AgentTest`):**
  - the room ticket request and its error messages;
  - tool validation and the 15 KiB reply limit;
  - transcript order, with interim text replaced by final text;
  - proposals that run only on a tap;
  - saving the verbatim conversation;
  - the notification's Stop action.
- **Server side (`agent/tests`):**
  - access-code checks and rate limiting;
  - ticket contents: fixed identity, a fresh room, only microphone and screen share, agent dispatch, 10 minutes;
  - tool forwarding and input checks;
  - both voice modes building.

Not yet run end to end:
- a real LiveKit room with a real phone;
- OpenRouter's speech-to-text and text-to-speech through LiveKit's OpenAI plugin (both are request/response, not streaming; if either misbehaves, switch to `realtime` mode);
- Swedish turn-taking (LiveKit's turn detectors do not cover Swedish, so turns end after 0.6 s of silence);
- screen sharing on Android 17.
