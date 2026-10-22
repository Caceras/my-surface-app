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


def test_a_self_hosted_server_shares_its_keys_file_and_public_address(tmp_path):
    config = tmp_path / "livekit.yaml"
    config.write_text("port: 7880\nrtc:\n  tcp_port: 17881\nkeys:\n  # generated\n  APIabc: 'secret-value'\nlogging:\n  level: info\n")
    env = {"LIVEKIT_URL": "ws://livekit:7880", "LIVEKIT_KEYS_FILE": str(config), "LIVEKIT_PUBLIC_URL": "wss://example.se/livekit",
           "AEGENTICA_ACCESS_CODE": "a-long-private-access-code"}
    s = Settings.from_env(env, need_access_code=True)
    assert (s.livekit_api_key, s.livekit_api_secret, s.public_url) == ("APIabc", "secret-value", "wss://example.se/livekit")
    with pytest.raises(ValueError, match="wss://"):
        Settings.from_env({**env, "LIVEKIT_PUBLIC_URL": ""}, need_access_code=True)
    with pytest.raises(ValueError, match="cannot be read"):
        Settings.from_env({**env, "LIVEKIT_KEYS_FILE": str(tmp_path / "missing.yaml")}, need_access_code=True)
