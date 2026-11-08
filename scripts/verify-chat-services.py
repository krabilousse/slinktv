"""Probe public chat services without reading, requesting, or persisting user tokens.

This verifies connectivity and public 7TV data, not authenticated subscriptions.
"""
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import websocket
from streamlink.plugins.twitch import TwitchAPI


def fetch(url, body=None, headers=None):
    request = urllib.request.Request(url, data=body, headers=headers or {})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read(8 * 1024 * 1024)


def probe():
    result = {"checked_at": datetime.now(timezone.utc).isoformat(), "authenticated_chat_tested": False}
    socket = websocket.create_connection("wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30", timeout=15)
    try:
        welcome = json.loads(socket.recv())
        assert welcome["metadata"]["message_type"] == "session_welcome"
        result["eventsub"] = {"welcome_received": True, "keepalive_seconds": welcome["payload"]["session"]["keepalive_timeout_seconds"]}
    finally:
        socket.close()
    payload = json.dumps({"query": 'query { user(login:"linkus7") { id } }'}).encode()
    user = json.loads(fetch("https://gql.twitch.tv/gql", payload, {"Client-ID": TwitchAPI.CLIENT_ID, "Content-Type": "application/json"}))
    channel_id = user["data"]["user"]["id"]
    global_set = json.loads(fetch("https://7tv.io/v3/emote-sets/global"))
    channel = json.loads(fetch("https://7tv.io/v3/users/twitch/" + channel_id))
    channel_set = channel.get("emote_set")
    if channel_set is None:
        channel_set = json.loads(fetch("https://7tv.io/v3/emote-sets/" + channel["emote_set_id"]))
    result["7tv"] = {"global_emotes": len(global_set["emotes"]), "linkus7_emotes": len(channel_set["emotes"])}
    assets = []
    for name in ["Clap", "7Cinema"]:
        attribution = json.loads(Path("app/src/test/resources/chat/attribution.json").read_text(encoding="utf-8"))
        source = next(item for item in attribution if item["name"] == name)
        data = fetch(source["url"])
        assert data[:4] == b"RIFF" and data[8:12] == b"WEBP"
        assets.append({"name": name, "bytes": len(data), "webp_header": True, "animation_chunk": b"ANIM" in data})
    result["cdn_assets"] = assets
    return result


if __name__ == "__main__":
    evidence = probe()
    output = Path("docs/evidence/chat-0.4.0-host.json")
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
