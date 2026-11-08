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


GOOD = "https://good.example/live/$channel"
BAD = "https://bad.example/live/$channel"


@pytest.fixture(autouse=True)
def forget_last_proxy():
    resolver.last_proxy = None
    yield
    resolver.last_proxy = None


def fake_proxies(working):
    calls = []

    def parse(session, url, **kwargs):
        calls.append(url)
        assert session.http.timeout <= 5, "a hanging proxy must fail over quickly"
        host = url.split("/")[2]
        if host not in working:
            raise OSError(f"{url} returned 502")
        return {"720p60": HLSStream(session, f"https://{host}.playlist.ttvnw.net/720.m3u8"),
                "1080p60": HLSStream(session, f"https://{host}.playlist.ttvnw.net/1080.m3u8"), "best": None}
    return patch.object(resolver.TwitchHLSStream, "parse_variant_playlist", side_effect=parse), calls


def direct_stream(twitch):
    twitch.return_value.streams.return_value = {"720p60": HLSStream(resolver.Streamlink(), "https://example.com/720.m3u8")}


def test_without_proxies_the_plugin_path_is_used_and_no_proxy_is_reported():
    proxy, calls = fake_proxies({"good.example"})
    with proxy, patch.object(resolver, "Twitch") as twitch:
        direct_stream(twitch)
        result = json.loads(resolver.resolve("Linkus7", "[]"))
    assert result["variants"][0]["url"] == "https://example.com/720.m3u8"
    assert "proxy" not in result and "proxy_failed" not in result
    assert calls == []


def test_failed_proxy_falls_through_to_the_next_and_the_winner_is_tried_first_next_time():
    proxy, calls = fake_proxies({"good.example"})
    with proxy, patch.object(resolver, "Twitch") as twitch:
        result = json.loads(resolver.resolve("Linkus7", json.dumps([BAD, GOOD])))
        assert result["proxy"] == "good.example"
        assert result["kind"] == "live" and result["channel"] == "linkus7"
        assert [v["name"] for v in result["variants"]] == ["1080p60", "720p60"]
        assert result["variants"][0]["url"] == "https://good.example.playlist.ttvnw.net/1080.m3u8"
        assert calls[1].startswith("https://good.example/live/linkus7?allow_source=true&")
        assert "supported_codecs=h264" in calls[1]
        calls.clear()
        assert json.loads(resolver.resolve("Linkus7", json.dumps([BAD, GOOD])))["proxy"] == "good.example"
        assert [url.split("/")[2] for url in calls] == ["good.example"]
        twitch.assert_not_called()


def test_when_every_proxy_fails_playback_falls_back_to_twitch_directly():
    proxy, calls = fake_proxies(set())
    with proxy, patch.object(resolver, "Twitch") as twitch:
        direct_stream(twitch)
        result = json.loads(resolver.resolve("Linkus7", json.dumps([BAD, "https://bad2.example/live/$channel"])))
    assert len(calls) == 2
    assert result["proxy_failed"] is True and "proxy" not in result
    assert result["variants"][0]["url"] == "https://example.com/720.m3u8"


def test_proxy_attempts_stop_after_the_overall_budget_and_play_directly():
    proxy, calls = fake_proxies({"good.example"})
    clock = iter(range(0, 100, 5))
    with proxy, patch.object(resolver.time, "monotonic", side_effect=lambda: next(clock)), patch.object(resolver, "Twitch") as twitch:
        direct_stream(twitch)
        templates = [f"https://bad{i}.example/live/$channel" for i in range(5)] + [GOOD]
        result = json.loads(resolver.resolve("Linkus7", json.dumps(templates)))
    assert result["proxy_failed"] is True
    assert [url.split("/")[2] for url in calls] == ["bad0.example", "bad1.example"]


@pytest.mark.parametrize("template", ["http://good.example/live/$channel", "https://good.example/live/", "https://user:pw@good.example/live/$channel", "good.example/live/$channel", 7])
def test_invalid_templates_are_never_requested(template):
    proxy, calls = fake_proxies({"good.example"})
    with proxy, patch.object(resolver, "Twitch") as twitch:
        direct_stream(twitch)
        result = json.loads(resolver.resolve("Linkus7", json.dumps([template])))
    assert calls == []
    assert "proxy" not in result and "proxy_failed" not in result


def test_proxy_and_direct_failures_never_expose_urls_or_exception_text():
    proxy, _ = fake_proxies(set())
    with proxy, patch.object(resolver, "Twitch") as twitch:
        twitch.return_value.streams.side_effect = PluginError("https://usher.ttvnw.net/?sig=SECRET")
        result = resolver.resolve("Linkus7", json.dumps([BAD]))
    data = json.loads(result)
    assert data["proxy_failed"] is True and "error" in data
    assert "SECRET" not in result and "https:" not in data["error"] and "bad.example" not in result


def test_swap_requests_an_android_autoplay_session_and_returns_its_streams():
    with patch.object(resolver, "TwitchAPI") as api, patch.object(resolver.TwitchHLSStream, "parse_variant_playlist") as parse:
        api.return_value.access_token.return_value = ("token", "SIG", '{"channel":"linkus7"}')
        parse.return_value = {"360p30": HLSStream(resolver.Streamlink(), "https://example.com/360.m3u8")}
        result = json.loads(resolver.resolve_swap("Linkus7"))
    assert api.call_args.kwargs["access_token_param"] == [("platform", "android"), ("playerType", "autoplay")]
    usher = parse.call_args.args[1]
    assert "/channel/hls/linkus7.m3u8" in usher and "platform=android" in usher and "sig=SIG" in usher
    assert [v["name"] for v in result["variants"]] == ["360p30"]


@pytest.mark.parametrize("token", [("error", None, "https://usher.ttvnw.net/?sig=SECRET"), ("token", None, None)])
def test_swap_failures_are_plain_errors(token):
    with patch.object(resolver, "TwitchAPI") as api:
        api.return_value.access_token.return_value = token
        result = resolver.resolve_swap("Linkus7")
    assert json.loads(result) == {"error": "No swap stream."}


def test_swap_network_errors_never_leak_urls():
    with patch.object(resolver, "TwitchAPI") as api:
        api.return_value.access_token.side_effect = OSError("https://gql.twitch.tv/?token=SECRET")
        result = resolver.resolve_swap("Linkus7")
    assert json.loads(result) == {"error": "No swap stream."}
