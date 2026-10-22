"""LiveKit's standard token endpoint for the owner's phone.

The phone sends the access code it was given; the server answers with a
ten-minute ticket for a fresh room that dispatches the Ægentica agent. The
LiveKit API secret never leaves this server, and the client cannot choose the
room, identity, permissions or agent.
"""

from __future__ import annotations

import hmac
import secrets
import time
from collections import defaultdict, deque
from datetime import timedelta

from aiohttp import web
from livekit import api

from .settings import AGENT_NAME, Settings

TICKET_TTL = timedelta(minutes=10)
MAX_PER_MINUTE = 20


def ticket(settings: Settings, room: str | None = None) -> str:
    room = room or f"aegentica-{secrets.token_hex(8)}"
    grants = api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True,
                             can_publish_data=True, can_publish_sources=["microphone", "screen_share"])
    return (api.AccessToken(settings.livekit_api_key, settings.livekit_api_secret)
            .with_identity("phone").with_name("Phone").with_grants(grants)
            .with_room_config(api.RoomConfiguration(agents=[api.RoomAgentDispatch(agent_name=AGENT_NAME)]))
            .with_ttl(TICKET_TTL)
            .to_jwt())


def create_app(settings: Settings, clock=time.monotonic) -> web.Application:
    recent: dict[str, deque[float]] = defaultdict(deque)
    expected = f"Bearer {settings.access_code}".encode()

    async def token(request: web.Request) -> web.Response:
        now = clock()
        calls = recent[request.remote or "?"]
        while calls and now - calls[0] > 60:
            calls.popleft()
        if len(calls) >= MAX_PER_MINUTE:
            return web.json_response({"error": "too many requests"}, status=429)
        calls.append(now)
        if not hmac.compare_digest(request.headers.get("Authorization", "").encode(), expected):
            return web.json_response({"error": "unauthorized"}, status=401)
        return web.json_response({"server_url": settings.public_url, "participant_token": ticket(settings)}, status=201)

    async def health(_: web.Request) -> web.Response:
        return web.json_response({"ok": True})

    app = web.Application(client_max_size=16 * 1024)
    app.router.add_post("/token", token)
    app.router.add_get("/healthz", health)
    return app


def main() -> None:
    import os
    settings = Settings.from_env(need_access_code=True)
    web.run_app(create_app(settings), host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))


if __name__ == "__main__":
    main()
