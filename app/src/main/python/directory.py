"""Anonymous directory via Twitch's unofficial web API, as used by Xtra.

Reuse Streamlink's public web client identifier. Never send SlinkTV user tokens
to this endpoint. Fail normally if Twitch changes or restricts the interface.
"""
import json
import re
from streamlink import Streamlink
from streamlink.plugins.twitch import TwitchAPI

TOP_QUERY = """query SlinkDirectory($languages:[Language!],$after:Cursor) {
  streams(first:30,after:$after,options:{sort:VIEWER_COUNT,broadcasterLanguages:$languages}) {
    edges { cursor node { id viewersCount previewImageURL
      game { name } broadcaster { id login displayName profileImageURL(width:150)
        broadcastSettings { title } } } }
    pageInfo { hasNextPage }
  }
}"""
SEARCH_QUERY = """query SlinkChannel($login:String!) {
  user(login:$login) { id login displayName profileImageURL(width:150)
    broadcastSettings { title }
    stream { id viewersCount previewImageURL game { name } }
  }
}"""


def card(user, stream, language):
    return {"id": user["id"], "login": user["login"], "name": user["displayName"],
            "title": (user.get("broadcastSettings") or {}).get("title", ""),
            "game": (stream.get("game") or {}).get("name", ""),
            "thumbnail": stream.get("previewImageURL", "").replace("{width}", "640").replace("{height}", "360"),
            "avatar": user.get("profileImageURL", ""), "viewers": stream.get("viewersCount", 0),
            "language": language}


def fetch(language="", cursor="", query=""):
    session = Streamlink(plugins_builtin=False)
    session.set_option("http-timeout", 15.0)
    try:
        if language and not re.fullmatch(r"[a-z]{2}", language):
            raise ValueError("Invalid language")
        if query:
            if not re.fullmatch(r"[a-zA-Z0-9_]{1,25}", query):
                return json.dumps({"error": "Enter an exact Twitch channel name, or sign in to search."})
            payload = {"query": SEARCH_QUERY, "variables": {"login": query.lower()}}
        else:
            payload = {"query": TOP_QUERY, "variables": {"languages": [language.upper()] if language else [], "after": cursor or None}}
        body = session.http.post("https://gql.twitch.tv/gql", json=payload,
                                 headers={"Client-ID": TwitchAPI.CLIENT_ID}, timeout=15).json()
        if any(error.get("extensions", {}).get("code") == "IntegrityCheckFailed" for error in body.get("errors", [])):
            return json.dumps({"error": "Sign in to load more streams." if cursor else "Sign in to browse streams.",
                               "sign_in_required": True})
        if body.get("errors") or not body.get("data"):
            return json.dumps({"error": "Guest directory unavailable. Retry or sign in."})
        if query:
            user = body["data"].get("user")
            items = [card(user, user["stream"], "")] if user and user.get("stream") else []
            return json.dumps({"items": items, "cursor": ""})
        page = body["data"]["streams"]
        edges = page["edges"]
        items = [card(e["node"]["broadcaster"], e["node"], language) for e in edges if e.get("node")]
        items.sort(key=lambda item: (-item["viewers"], item["id"]))
        next_cursor = edges[-1]["cursor"] if edges and page["pageInfo"]["hasNextPage"] else ""
        return json.dumps({"items": items, "cursor": next_cursor})
    except Exception:
        return json.dumps({"error": "Guest directory unavailable. Check the connection, retry, or sign in."})
    finally:
        session.http.close()
