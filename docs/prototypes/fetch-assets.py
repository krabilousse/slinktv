"""Download small, attributed image fixtures for the throwaway TV UI prototype."""
import concurrent.futures
import io
import json
from pathlib import Path
import urllib.request
from PIL import Image

ROOT = Path(__file__).parent
ASSETS = ROOT / "assets"
ASSETS.mkdir(exist_ok=True)


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SlinkTV-UI-Prototype/1.0"}), timeout=30).read()


def save(name, url, width):
    image = Image.open(io.BytesIO(fetch(url))).convert("RGB")
    image.thumbnail((width, width))
    image.save(ASSETS / (name + ".jpg"), quality=84, optimize=True)
    return {"file": name + ".jpg", "source": url, "usage": "Illustrative prototype fixture; not current live stream data"}


def steam(name, app_id, index=0):
    data = json.loads(fetch(f"https://store.steampowered.com/api/appdetails?appids={app_id}"))[str(app_id)]["data"]
    return save(name, data["screenshots"][index]["path_full"], 1440)


def avatar(login):
    url = fetch(f"https://decapi.me/twitch/avatar/{login}").decode().strip()
    if not url.startswith("https://static-cdn.jtvnw.net/"):
        raise ValueError("Expected Twitch image CDN")
    return save(login.lower(), url, 120)


if __name__ == "__main__":
    jobs = [(save, ("zelda", "https://stock.wikimini.org/w/images/a/a0/The_Legend_of_Zelda_Breath_of_the_Wild_-_game_-_screenshot.jpg", 1440)),
            (steam, ("counterstrike", 730, 1)), (steam, ("eldenring", 1245620, 0)),
            (steam, ("stardew", 413150, 0)), (steam, ("hades", 1145360, 1)), (steam, ("dota", 570, 0))]
    jobs += [(avatar, (name,)) for name in ("linkus7", "lirik", "shroud", "cohhcarnage", "eslcs", "northernlion", "dota2ti")]
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        results = list(pool.map(lambda job: job[0](*job[1]), jobs))
    (ROOT / "image-sources.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"Saved {len(results)} attributed image fixtures.")
