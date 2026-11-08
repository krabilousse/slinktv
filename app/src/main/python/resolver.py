"""On-device Streamlink resolution. Media3 owns HLS loading and decoding."""
import json
import re
from urllib.parse import urlsplit

from streamlink import Streamlink
from streamlink.exceptions import PluginError
from streamlink.plugins.twitch import Twitch
from streamlink.stream.hls import HLSStream

DEMO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"


def channel_name(value):
    value = value.strip()
    if "://" in value or value.lower().startswith(("twitch.tv/", "www.twitch.tv/", "m.twitch.tv/")):
        parsed = urlsplit(value if "://" in value else "https://" + value)
        if parsed.scheme != "https" or parsed.hostname not in ("twitch.tv", "www.twitch.tv", "m.twitch.tv"):
            raise ValueError("Enter a Twitch channel name or an https://twitch.tv/channel URL.")
        value = parsed.path.strip("/")
    if not re.fullmatch(r"[A-Za-z0-9_]{1,25}", value):
        raise ValueError("Enter a channel name, for example Linkus7. Use the Videos tab for archived broadcasts.")
    if value.lower() in {"videos", "directory", "downloads", "settings", "subscriptions"}:
        raise ValueError("Enter a channel name, not a Twitch navigation page.")
    return value.lower()


def resolve(value):
    try:
        channel = channel_name(value)
    except ValueError as exc:
        return json.dumps({"error": str(exc)})
    return resolve_url("https://www.twitch.tv/" + channel, channel, False)


def resolve_video(value):
    value = str(value).strip()
    if not re.fullmatch(r"[0-9]{1,30}", value):
        return json.dumps({"error": "Invalid Twitch video ID."})
    return resolve_url("https://www.twitch.tv/videos/" + value, "", True)


def resolve_url(url, channel, video):
    session = Streamlink(plugins_builtin=False)
    session.set_option("http-timeout", 15.0)
    # Desktop Chromium cannot be launched on Android. Fail visibly if Twitch requires it.
    session.set_option("webbrowser", False)
    try:
        plugin = Twitch(session, url)
        streams = plugin.streams()
        if not streams:
            return json.dumps({"error": "This video is unavailable, expired, or restricted." if video else f"{channel} is offline or has no publicly available stream. Try another channel or retry when live."})
        variants = []
        for name, stream in streams.items():
            if name in ("best", "worst") or "_portrait" in name.lower() or not isinstance(stream, HLSStream):
                continue
            variants.append({"name": name, "url": stream.to_url()})
        variants.sort(key=lambda item: quality_rank(item["name"]), reverse=True)
        if not variants:
            return json.dumps({"error": "Twitch did not return a supported HLS stream."})
        return json.dumps({
            "channel": channel,
            "kind": "vod" if video else "live",
            "variants": variants,
            "headers": {key: session.http.headers[key] for key in ("User-Agent", "Referer", "Origin") if key in session.http.headers},
        })
    except PluginError:
        return json.dumps({"error": "Twitch could not provide this video. It may be expired, restricted, or require a browser integrity check." if video else "Twitch could not provide this stream. It may be offline, restricted, or require a browser integrity check. Retry or try another channel."})
    except Exception:
        # Network exceptions may contain signed playback URLs; never expose them in the UI/logs.
        return json.dumps({"error": "Could not reach Twitch. Check your connection and retry."})
    finally:
        session.http.close()


def quality_rank(name):
    match = re.match(r"(\d+)p(\d*)", name)
    return (int(match[1]), int(match[2] or 0)) if match else (0, 0)
