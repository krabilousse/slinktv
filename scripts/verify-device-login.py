"""Check Twitch's pre-consent device flow without printing or saving login codes."""
import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def post(endpoint, fields):
    request = urllib.request.Request(
        "https://id.twitch.tv/oauth2/" + endpoint,
        data=urllib.parse.urlencode(fields).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client_id", help="Public Client ID, never a client secret")
    args = parser.parse_args()
    status, device = post("device", {"client_id": args.client_id, "scopes": "user:read:follows"})
    if status != 200:
        raise SystemExit(f"Device request failed: HTTP {status}")
    time.sleep(max(5, device["interval"]))
    poll_status, poll = post("token", {
        "client_id": args.client_id, "scopes": "user:read:follows",
        "device_code": device["device_code"],
        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
    })
    pending = poll.get("message", poll.get("error")) == "authorization_pending"
    print(json.dumps({
        "device_http_status": status,
        "verification_host": urllib.parse.urlparse(device["verification_uri"]).hostname,
        "expires_in": device["expires_in"], "interval": device["interval"],
        "poll_http_status": poll_status, "authorization_pending": pending,
        "consent_completed": False,
    }, indent=2))
    if not pending:
        raise SystemExit("Unexpected pre-consent response; credentials omitted")
