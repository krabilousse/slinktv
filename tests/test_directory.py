import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("slink_directory", Path(__file__).parents[1] / "app/src/main/python/directory.py")
directory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(directory)


class Http:
    def __init__(self, body):
        self.body, self.calls, self.closed = body, [], False

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self

    def json(self):
        return self.body

    def close(self):
        self.closed = True


class Session:
    def __init__(self, body):
        self.http = Http(body)

    def set_option(self, *args):
        pass


def node(name, viewers):
    return {"id": "stream-" + name, "viewersCount": viewers, "broadcaster": {
        "id": name, "login": name, "displayName": name, "broadcastSettings": {"title": "Live"}}}


def test_popular_language_cursor_and_viewer_order(monkeypatch):
    session = Session({"data": {"streams": {"edges": [
        {"cursor": "one", "node": node("small", 2)}, {"cursor": "two", "node": node("large", 40)}],
        "pageInfo": {"hasNextPage": True}}}})
    monkeypatch.setattr(directory, "Streamlink", lambda **kwargs: session)
    page = json.loads(directory.fetch("fr", "previous"))
    assert [item["login"] for item in page["items"]] == ["large", "small"]
    assert page["cursor"] == "two"
    request = session.http.calls[0][1]
    assert request["json"]["variables"] == {"languages": ["FR"], "after": "previous"}
    assert "first:30" in request["json"]["query"]
    assert "Authorization" not in request["headers"]
    assert session.http.closed


def test_graphql_errors_are_not_presented_as_an_empty_directory(monkeypatch):
    session = Session({"errors": [{"message": "arbitrary server text"}], "data": {"streams": None}})
    monkeypatch.setattr(directory, "Streamlink", lambda **kwargs: session)
    result = json.loads(directory.fetch())
    assert "error" in result and "arbitrary server text" not in result["error"]


def test_exact_channel_offline_and_invalid_input(monkeypatch):
    session = Session({"data": {"user": None}})
    monkeypatch.setattr(directory, "Streamlink", lambda **kwargs: session)
    assert json.loads(directory.fetch(query="Linkus7"))["items"] == []
    assert session.http.calls[0][1]["json"]["variables"]["login"] == "linkus7"
    session.http.calls.clear()
    assert "error" in json.loads(directory.fetch(query="https://invalid.example"))
    assert session.http.calls == []


def test_integrity_rejection_offers_sign_in_instead_of_empty_or_retry_loop(monkeypatch):
    session = Session({"errors": [{"message": "failed integrity check", "extensions": {"code": "IntegrityCheckFailed"}}],
                       "data": {"streams": None}})
    monkeypatch.setattr(directory, "Streamlink", lambda **kwargs: session)
    result = json.loads(directory.fetch("en", "next-page"))
    assert result["sign_in_required"] is True
    assert result["error"] == "Sign in to load more streams."
    assert "items" not in result
