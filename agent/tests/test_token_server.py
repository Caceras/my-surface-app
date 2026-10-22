import jwt
import pytest

from aegentica_agent.settings import AGENT_NAME, Settings
from aegentica_agent.token_server import MAX_PER_MINUTE, create_app

ENV = {"LIVEKIT_URL": "wss://lk.example.se", "LIVEKIT_API_KEY": "key", "LIVEKIT_API_SECRET": "secret-that-is-long-enough-for-hs256",
       "AEGENTICA_ACCESS_CODE": "a-long-private-access-code"}


@pytest.fixture
def settings():
    return Settings.from_env(ENV, need_access_code=True)


async def test_only_the_access_code_gets_a_ticket(aiohttp_client, settings):
    client = await aiohttp_client(create_app(settings))
    assert (await client.post("/token")).status == 401
    assert (await client.post("/token", headers={"Authorization": "Bearer wrong"})).status == 401
    ok = await client.post("/token", headers={"Authorization": "Bearer a-long-private-access-code"}, json={"room_name": "chosen", "participant_identity": "admin"})
    assert ok.status == 201
    body = await ok.json()
    assert body["server_url"] == "wss://lk.example.se"
    claims = jwt.decode(body["participant_token"], "secret-that-is-long-enough-for-hs256", algorithms=["HS256"])
    # The client cannot pick the room, identity, permissions or agent.
    assert claims["sub"] == "phone"
    assert claims["video"]["room"].startswith("aegentica-") and claims["video"]["room"] != "chosen"
    assert claims["video"]["roomJoin"] is True
    assert set(claims["video"]["canPublishSources"]) == {"microphone", "screen_share"}
    assert claims["roomConfig"]["agents"][0]["agentName"] == AGENT_NAME
    assert claims["exp"] - claims["nbf"] <= 600
    second = await (await client.post("/token", headers={"Authorization": "Bearer a-long-private-access-code"})).json()
    assert jwt.decode(second["participant_token"], options={"verify_signature": False})["video"]["room"] != claims["video"]["room"]


async def test_requests_are_rate_limited(aiohttp_client, settings):
    client = await aiohttp_client(create_app(settings, clock=lambda: 1000.0))
    codes = [(await client.post("/token", headers={"Authorization": "Bearer nope"})).status for _ in range(MAX_PER_MINUTE + 1)]
    assert codes[:MAX_PER_MINUTE] == [401] * MAX_PER_MINUTE and codes[-1] == 429


async def test_health(aiohttp_client, settings):
    client = await aiohttp_client(create_app(settings))
    assert (await client.get("/healthz")).status == 200
