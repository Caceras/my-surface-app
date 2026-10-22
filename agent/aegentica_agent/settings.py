"""Configuration from environment variables, validated before anything starts."""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Mapping

AGENT_NAME = "aegentica"


@dataclass(frozen=True)
class Settings:
    livekit_url: str
    livekit_api_key: str
    livekit_api_secret: str
    # "pipeline": speech-to-text, an OpenRouter model, text-to-speech.
    # "realtime": Gemini Live speech-to-speech with the owner's Google key.
    mode: str = "pipeline"
    openrouter_api_key: str = ""
    google_api_key: str = ""
    llm_model: str = "google/gemini-3.8-flash"
    stt_model: str = "openai/whisper-large-v3"
    tts_model: str = "microsoft/mai-voice-2.1-flash"
    tts_voice: str = "sv-SE-Harper"
    realtime_model: str = "gemini-3.8-live"
    language: str = "sv"
    access_code: str = ""

    @staticmethod
    def from_env(env: Mapping[str, str] = os.environ, *, need_access_code: bool = False) -> "Settings":
        def get(name: str, default: str = "") -> str:
            return env.get(name, default).strip()

        s = Settings(
            livekit_url=get("LIVEKIT_URL"),
            livekit_api_key=get("LIVEKIT_API_KEY"),
            livekit_api_secret=get("LIVEKIT_API_SECRET"),
            mode=get("AEGENTICA_MODE", "pipeline").lower(),
            openrouter_api_key=get("OPENROUTER_API_KEY"),
            google_api_key=get("GOOGLE_API_KEY"),
            llm_model=get("AEGENTICA_LLM", Settings.llm_model),
            stt_model=get("AEGENTICA_STT", Settings.stt_model),
            tts_model=get("AEGENTICA_TTS", Settings.tts_model),
            tts_voice=get("AEGENTICA_VOICE", Settings.tts_voice),
            realtime_model=get("AEGENTICA_REALTIME", Settings.realtime_model),
            language=get("AEGENTICA_LANGUAGE", Settings.language),
            access_code=get("AEGENTICA_ACCESS_CODE"),
        )
        problems = []
        if not s.livekit_url.startswith(("wss://", "ws://")):
            problems.append("LIVEKIT_URL must be the wss:// address of your LiveKit project or server")
        if not s.livekit_api_key or not s.livekit_api_secret:
            problems.append("LIVEKIT_API_KEY and LIVEKIT_API_SECRET are required")
        if s.mode not in ("pipeline", "realtime"):
            problems.append("AEGENTICA_MODE must be pipeline or realtime")
        if s.mode == "pipeline" and not s.openrouter_api_key and not need_access_code:
            problems.append("OPENROUTER_API_KEY is required in pipeline mode")
        if s.mode == "realtime" and not s.google_api_key and not need_access_code:
            problems.append("GOOGLE_API_KEY is required in realtime mode")
        if need_access_code and len(s.access_code) < 16:
            problems.append("AEGENTICA_ACCESS_CODE must be at least 16 characters; the phone sends it to get a room ticket")
        if problems:
            raise ValueError("; ".join(problems))
        return s
