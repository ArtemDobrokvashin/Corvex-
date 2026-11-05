from __future__ import annotations

import importlib.util
import json
import stat
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "corvex_telegram_bot", ROOT / "infra/alert-relay/telegram_bot.py"
)
assert SPEC and SPEC.loader
telegram = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = telegram
SPEC.loader.exec_module(telegram)

TOKEN = "123456:telegram-secret"


class StaticResponse:
    status = 200

    def __init__(self, result):
        self.raw = json.dumps({"ok": True, "result": result}).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, _limit):
        return self.raw


def update(update_id, text, chat_id=123456, language="ru", title="Artem"):
    return {
        "update_id": update_id,
        "message": {
            "text": text,
            "from": {"language_code": language},
            "chat": {
                "id": chat_id,
                "type": "private" if chat_id > 0 else "supergroup",
                "first_name": title,
                "title": "Corvex admins",
            },
        },
    }


class TelegramBotClientTest(unittest.TestCase):
    def test_discovery_returns_selectable_chats_without_acknowledging_updates(self):
        requests = []

        def fake_urlopen(request, **_kwargs):
            requests.append(request)
            if request.full_url.endswith("/getMe"):
                return StaticResponse({"username": "corvexmail_bot"})
            return StaticResponse([
                update(10, "/start"),
                update(11, "/id@corvexmail_bot", chat_id=-100123, title="Admins"),
            ])

        client = telegram.TelegramBotClient(
            urlopen=fake_urlopen, context=lambda: None, timeout=lambda: 2
        )
        result = client.discover("", TOKEN)

        self.assertEqual(result["botUsername"], "corvexmail_bot")
        self.assertEqual([chat["id"] for chat in result["chats"]], ["123456", "-100123"])
        get_updates = json.loads(requests[1].data)
        self.assertNotIn("offset", get_updates)
        self.assertNotIn(TOKEN, str(result))
        self.assertTrue(all("/bot" + TOKEN + "/" in request.full_url for request in requests))

    def test_commands_answer_and_advance_offset_without_replays(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "telegram-state.json"
            batches = [[
                update(20, "/start@corvexmail_bot"),
                update(21, "/id", language="en"),
                update(22, "/id@another_bot", chat_id=999),
            ], []]
            requests = []

            def fake_urlopen(request, **_kwargs):
                requests.append(request)
                if request.full_url.endswith("/getMe"):
                    return StaticResponse({"username": "corvexmail_bot"})
                if request.full_url.endswith("/getUpdates"):
                    return StaticResponse(batches.pop(0))
                return StaticResponse({"message_id": 1})

            client = telegram.TelegramBotClient(
                urlopen=fake_urlopen,
                context=lambda: None,
                timeout=lambda: 2,
                state_path=state,
            )
            self.assertEqual(client.poll_once(TOKEN), 3)
            self.assertEqual(client.poll_once(TOKEN), 0)

            sent = [json.loads(request.data) for request in requests if request.full_url.endswith("/sendMessage")]
            self.assertEqual(len(sent), 2)
            self.assertIn("чат обнаружен", sent[0]["text"])
            self.assertIn("Chat ID: 123456", sent[0]["text"])
            self.assertEqual(sent[1]["text"], "Chat ID: 123456")
            self.assertFalse(any(chat["chat_id"] == "999" for chat in sent))
            second_poll = next(
                json.loads(request.data) for request in requests
                if request.full_url.endswith("/getUpdates")
                and json.loads(request.data).get("offset") == 23
            )
            self.assertEqual(second_poll["offset"], 23)
            self.assertEqual(stat.S_IMODE(state.stat().st_mode), 0o600)
            self.assertNotIn(TOKEN, state.read_text(encoding="utf-8"))

    def test_recent_polled_chat_is_available_to_discovery_after_acknowledgement(self):
        calls = []

        def fake_urlopen(request, **_kwargs):
            calls.append(request.full_url)
            if request.full_url.endswith("/getMe"):
                return StaticResponse({"username": "corvexmail_bot"})
            if request.full_url.endswith("/getUpdates"):
                return StaticResponse([update(30, "/start")] if len(calls) == 2 else [])
            return StaticResponse({"message_id": 1})

        client = telegram.TelegramBotClient(
            urlopen=fake_urlopen, context=lambda: None, timeout=lambda: 2
        )
        client.poll_once(TOKEN)
        result = client.discover("", TOKEN)
        self.assertEqual(result["chats"][0]["id"], "123456")

    def test_invalid_token_and_network_errors_are_sanitized(self):
        def unexpected(*_args, **_kwargs):
            raise AssertionError("invalid token must not cause a network request")

        client = telegram.TelegramBotClient(
            urlopen=unexpected, context=lambda: None, timeout=lambda: 2
        )
        with self.assertRaises(telegram.TelegramBotError):
            client.discover("not-a-token", "")

        def offline(_request, **_kwargs):
            raise urllib.error.URLError("https://api.telegram.org/bot" + TOKEN + "/getMe")

        client = telegram.TelegramBotClient(
            urlopen=offline, context=lambda: None, timeout=lambda: 2
        )
        with self.assertRaises(telegram.TelegramBotError) as raised:
            client.get_username(TOKEN)
        self.assertNotIn(TOKEN, str(raised.exception))
        self.assertNotIn("api.telegram.org", str(raised.exception))

    def test_rejects_malformed_updates_and_non_ascii_command_mentions(self):
        client = telegram.TelegramBotClient(
            urlopen=lambda *_args, **_kwargs: None,
            context=lambda: None,
            timeout=lambda: 2,
        )
        with self.assertRaises(telegram.TelegramBotError):
            client.updates([{"update_id": True}])
        message = {"text": "/id@другой", "chat": {"id": 1}}
        self.assertEqual(telegram.command_for_bot(message, "corvexmail_bot"), "")


if __name__ == "__main__":
    unittest.main()
