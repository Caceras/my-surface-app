import pytest

from aegentica_agent.settings import Settings

BASE = {"LIVEKIT_URL": "wss://lk.example.se", "LIVEKIT_API_KEY": "k", "LIVEKIT_API_SECRET": "s"}


def test_pipeline_needs_openrouter_and_realtime_needs_google():
    assert Settings.from_env({**BASE, "OPENROUTER_API_KEY": "or"}).llm_model == "google/gemini-3.8-flash"
    with pytest.raises(ValueError, match="OPENROUTER_API_KEY"):
        Settings.from_env(BASE)
    with pytest.raises(ValueError, match="GOOGLE_API_KEY"):
        Settings.from_env({**BASE, "AEGENTICA_MODE": "realtime"})
    assert Settings.from_env({**BASE, "AEGENTICA_MODE": "realtime", "GOOGLE_API_KEY": "g"}).mode == "realtime"


def test_token_server_needs_a_long_access_code_and_a_secure_url():
    with pytest.raises(ValueError, match="at least 16"):
        Settings.from_env({**BASE, "AEGENTICA_ACCESS_CODE": "short"}, need_access_code=True)
    with pytest.raises(ValueError, match="LIVEKIT_URL"):
        Settings.from_env({**BASE, "LIVEKIT_URL": "https://lk.example.se", "OPENROUTER_API_KEY": "or"})
