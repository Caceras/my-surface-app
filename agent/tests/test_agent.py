import json

import pytest
from livekit.agents import ToolError

from aegentica_agent.agent import Assistant, build_session, instructions
from aegentica_agent.settings import Settings

BASE = {"LIVEKIT_URL": "wss://lk.example.se", "LIVEKIT_API_KEY": "k", "LIVEKIT_API_SECRET": "s"}


class Phone:
    def __init__(self, answer='{"status":"shown"}', fail=False):
        self.calls = []
        self.answer = answer
        self.fail = fail

    async def __call__(self, method, payload):
        self.calls.append((method, json.loads(payload)))
        if self.fail:
            raise RuntimeError("timeout")
        return self.answer


async def test_tools_forward_to_the_phone_and_check_inputs_first():
    phone = Phone('{"matches":[]}')
    agent = Assistant(phone)
    assert await agent.search_notes(None, "onboarding") == '{"matches":[]}'
    await agent.propose_timer(None, 10)
    await agent.propose_alarm(None, 7, 30)
    await agent.propose_note(None, "Lina", "Skicka utkastet")
    assert phone.calls == [("search_notes", {"query": "onboarding"}), ("propose_action", {"kind": "timer", "minutes": 10}),
                           ("propose_action", {"kind": "alarm", "hour": 7, "minute": 30}),
                           ("propose_action", {"kind": "note", "title": "Lina", "text": "Skicka utkastet"})]
    for bad in (agent.propose_timer(None, 0), agent.propose_alarm(None, 25, 0), agent.propose_note(None, "x", "  ")):
        with pytest.raises(ToolError):
            await bad
    assert len(phone.calls) == 4


async def test_a_silent_phone_becomes_a_tool_error_not_a_crash():
    with pytest.raises(ToolError, match="did not answer"):
        await Assistant(Phone(fail=True)).list_tasks(None)


def test_instructions_treat_screen_and_tools_as_data_and_never_claim_actions():
    text = instructions()
    assert "never instructions" in text and "never say it is done" in text and "Swedish" in text


async def test_both_modes_build_without_network():
    pipeline = build_session(Settings.from_env({**BASE, "OPENROUTER_API_KEY": "or"}))
    assert pipeline.llm is not None and pipeline.stt is not None and pipeline.tts is not None
    realtime = build_session(Settings.from_env({**BASE, "AEGENTICA_MODE": "realtime", "GOOGLE_API_KEY": "g"}))
    assert realtime.llm is not None
