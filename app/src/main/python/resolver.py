"""On-device Streamlink resolution. Media3 owns HLS loading and decoding."""
import json
import re
import time
from urllib.parse import urlsplit

from streamlink import Streamlink
from streamlink.exceptions import PluginError
from streamlink.plugins.twitch import Twitch, TwitchHLSStream
from streamlink.stream.hls import HLSStream

DEMO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
PROXY_QUERY = "allow_source=true&allow_audio_only=true&fast_bread=true&include_unavailable=true&supported_codecs=h264"
PROXY_TIMEOUT = 4.0
PROXY_BUDGET = 12.0
last_proxy = None


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


def resolve(value, proxies="[]"):
    try:
        channel = channel_name(value)
    except ValueError as exc:
        return json.dumps({"error": str(exc)})
    url = "https://www.twitch.tv/" + channel
    templates = proxy_templates(proxies)
    if not templates:
        return json.dumps(resolve_url(url, channel, False))
    return json.dumps(resolve_proxied(channel, templates) or {**resolve_url(url, channel, False), "proxy_failed": True})


def proxy_templates(proxies):
    try:
        values = json.loads(proxies)
    except (TypeError, ValueError):
        return []
    if not isinstance(values, list):
        return []
    templates = []
    for value in values:
        if not isinstance(value, str) or "$channel" not in value:
            continue
        try:
            parsed = urlsplit(value)
        except ValueError:
            continue
        if parsed.scheme == "https" and parsed.hostname and parsed.username is None and parsed.password is None and value not in templates:
            templates.append(value)
    return templates


def resolve_proxied(channel, templates):
    global last_proxy
    start = templates.index(last_proxy) if last_proxy in templates else 0
    session = new_session(PROXY_TIMEOUT)
    session.http.headers.update({"Referer": "https://player.twitch.tv", "Origin": "https://player.twitch.tv"})
    deadline = time.monotonic() + PROXY_BUDGET
    try:
        for template in templates[start:] + templates[:start]:
            if time.monotonic() >= deadline:
                return None
            url = template.replace("$channel", channel)
            try:
                streams = TwitchHLSStream.parse_variant_playlist(session, url + ("&" if "?" in url else "?") + PROXY_QUERY, name_key="name", check_streams=True)
                variants = playable(streams)
            except Exception:
                continue
            if variants:
                last_proxy = template
                return {"channel": channel, "kind": "live", "variants": variants, "headers": playback_headers(session), "proxy": urlsplit(template).hostname}
        return None
    finally:
        session.http.close()


def resolve_video(value):
    value = str(value).strip()
    if not re.fullmatch(r"[0-9]{1,30}", value):
        return json.dumps({"error": "Invalid Twitch video ID."})
    return json.dumps(resolve_url("https://www.twitch.tv/videos/" + value, "", True))


def new_session(timeout):
    session = Streamlink(plugins_builtin=False)
    session.set_option("http-timeout", timeout)
    # Desktop Chromium cannot be launched on Android. Fail visibly if Twitch requires it.
    session.set_option("webbrowser", False)
    return session


def playable(streams):
    variants = [{"name": name, "url": stream.to_url()} for name, stream in streams.items()
                if name not in ("best", "worst") and "_portrait" not in name.lower() and isinstance(stream, HLSStream)]
    variants.sort(key=lambda item: quality_rank(item["name"]), reverse=True)
    return variants


def playback_headers(session):
    return {key: session.http.headers[key] for key in ("User-Agent", "Referer", "Origin") if key in session.http.headers}


def resolve_url(url, channel, video):
    session = new_session(15.0)
    try:
        plugin = Twitch(session, url)
        streams = plugin.streams()
        if not streams:
            return {"error": "This video is unavailable, expired, or restricted." if video else f"{channel} is offline or has no publicly available stream. Try another channel or retry when live."}
        variants = playable(streams)
        if not variants:
            return {"error": "Twitch did not return a supported HLS stream."}
        return {"channel": channel, "kind": "vod" if video else "live", "variants": variants, "headers": playback_headers(session)}
    except PluginError:
        return {"error": "Twitch could not provide this video. It may be expired, restricted, or require a browser integrity check." if video else "Twitch could not provide this stream. It may be offline, restricted, or require a browser integrity check. Retry or try another channel."}
    except Exception:
        # Network exceptions may contain signed playback URLs; never expose them in the UI/logs.
        return {"error": "Could not reach Twitch. Check your connection and retry."}
    finally:
        session.http.close()


def quality_rank(name):
    match = re.match(r"(\d+)p(\d*)", name)
    return (int(match[1]), int(match[2] or 0)) if match else (0, 0)
