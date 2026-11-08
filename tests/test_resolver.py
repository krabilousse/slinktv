import json
import sys
from pathlib import Path
from unittest.mock import patch

import pytest
from streamlink.exceptions import PluginError
from streamlink.stream.hls import HLSStream

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import resolver


@pytest.mark.parametrize("value", ["Linkus7", " linkus7 ", "https://www.twitch.tv/Linkus7/", "twitch.tv/Linkus7?ref=home"])
def test_channel_inputs(value):
    assert resolver.channel_name(value) == "linkus7"


@pytest.mark.parametrize("value", ["", "https://twitch.tv.evil.test/linkus7", "https://example.com/linkus7", "a/b", "https://twitch.tv/videos/123", "https://twitch.tv/directory", "file:///etc/passwd", "x" * 26])
def test_invalid_inputs_do_not_start_network(value):
    with patch.object(resolver, "Streamlink") as session:
        assert "error" in json.loads(resolver.resolve(value))
        session.assert_not_called()


def test_qualities_are_playable_sorted_and_aliases_removed():
    with patch.object(resolver, "Twitch") as twitch:
        session = resolver.Streamlink()
        low = HLSStream(session, "https://example.com/360.m3u8")
        high = HLSStream(session, "https://example.com/1080.m3u8")
        twitch.return_value.streams.return_value = {"360p30": low, "1080p60": high, "best": high, "worst": low}
        result = json.loads(resolver.resolve("Linkus7"))
        assert result["channel"] == "linkus7"
        assert [v["name"] for v in result["variants"]] == ["1080p60", "360p30"]
        assert result["variants"][0]["url"] == "https://example.com/1080.m3u8"


def test_offline_channel_has_actionable_message():
    with patch.object(resolver, "Twitch") as twitch:
        twitch.return_value.streams.return_value = {}
        assert "offline" in json.loads(resolver.resolve("linkus7"))["error"]


@pytest.mark.parametrize("value", ["https://twitch.tv/videos/123", "-123", "123/456", "", "x", "1" * 31])
def test_vod_ids_cannot_redirect_extraction(value):
    with patch.object(resolver, "Streamlink") as session:
        assert "error" in json.loads(resolver.resolve_video(value))
        session.assert_not_called()


def test_vod_resolution_has_explicit_kind_and_uses_canonical_twitch_url():
    with patch.object(resolver, "Twitch") as twitch:
        session = resolver.Streamlink()
        twitch.return_value.streams.return_value = {"720p60": HLSStream(session, "https://example.com/vod.m3u8")}
        result = json.loads(resolver.resolve_video("2920342167"))
        assert twitch.call_args.args[1] == "https://www.twitch.tv/videos/2920342167"
        assert result["kind"] == "vod"
        assert result["variants"][0]["name"] == "720p60"
        assert not any(name.lower() == "authorization" for name in result["headers"])


def test_portrait_renditions_never_reach_the_player():
    with patch.object(resolver, "Twitch") as twitch:
        session = resolver.Streamlink()
        stream = HLSStream(session, "https://example.com/playlist.m3u8")
        twitch.return_value.streams.return_value = {
            "2160p60_portrait": stream, "1080p60": stream,
            "720p60_PORTRAIT": stream, "480p30": stream,
            "best": stream, "worst": stream,
        }
        result = json.loads(resolver.resolve("Linkus7"))
        assert [v["name"] for v in result["variants"]] == ["1080p60", "480p30"]


def test_portrait_only_stream_is_not_offered_as_a_landscape_fallback():
    with patch.object(resolver, "Twitch") as twitch:
        session = resolver.Streamlink()
        twitch.return_value.streams.return_value = {
            "1080p60_portrait": HLSStream(session, "https://example.com/portrait.m3u8")
        }
        assert "error" in json.loads(resolver.resolve("Linkus7"))


@pytest.mark.parametrize("error", [PluginError("https://signed.example/?token=SECRET"), OSError("SECRET")])
def test_failures_do_not_leak_signed_urls_and_session_closes(error):
    with patch.object(resolver, "Twitch") as twitch, patch.object(resolver, "Streamlink") as session:
        twitch.return_value.streams.side_effect = error
        result = resolver.resolve("linkus7")
        assert "error" in json.loads(result)
        assert "SECRET" not in result
        session.return_value.http.close.assert_called_once()
