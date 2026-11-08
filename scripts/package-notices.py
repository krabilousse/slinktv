"""Capture installed Python dependency versions and license texts after the first build."""
from email.parser import Parser
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
packages = root / "app/build/python/pip/debug/common"
pins = []
sections = ["SlinkTV third-party software notices\n\nSource is available in the accompanying project.\n"]
for dist in sorted(packages.glob("*.dist-info")):
    meta = Parser().parsestr((dist / "METADATA").read_text(encoding="utf-8"))
    pins.append(f"{meta['Name']}=={meta['Version']}")
    sections.append(f"\n{'=' * 60}\n{meta['Name']} {meta['Version']}\n")
    sections.extend(meta.get_all("Project-URL", []))
    if meta.get("Home-page"):
        sections.append(meta["Home-page"])
    sections.append("License: " + str(meta.get("License-Expression") or meta.get("License") or "See license text / source"))
    for f in sorted(dist.rglob("*")):
        if f.is_file() and ("license" in f.name.lower() or "copying" in f.name.lower() or "notice" in f.name.lower()):
            sections.append(f.read_text(encoding="utf-8", errors="replace"))

for name, url in [
    ("AndroidX Media3, Activity, Lifecycle, and RecyclerView (Apache-2.0)", "https://raw.githubusercontent.com/androidx/media/release/LICENSE"),
    ("Glide 4.16.0", "https://raw.githubusercontent.com/bumptech/glide/v4.16.0/LICENSE"),
    ("ZXing 3.5.3 (Apache-2.0)", "https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/LICENSE"),
    ("OkHttp 5.3.2 (Apache-2.0)", "https://raw.githubusercontent.com/square/okhttp/parent-5.3.2/LICENSE.txt"),
    ("Okio 3.16.4 (Apache-2.0)", "https://raw.githubusercontent.com/square/okio/parent-3.16.4/LICENSE.txt"),
    ("Kotlin runtime 2.2.21 (Apache-2.0)", "https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.21/license/LICENSE.txt"),
    ("Chaquopy", "https://raw.githubusercontent.com/chaquo/chaquopy/master/LICENSE.txt"),
    ("Python", "https://raw.githubusercontent.com/python/cpython/3.11/LICENSE"),
]:
    try:
        sections.append(f"\n{'=' * 60}\n{name}\n{url}\n" + urllib.request.urlopen(url).read().decode())
    except Exception as exc:
        raise SystemExit(f"Unable to fetch {name} license: {exc}")

(root / "app/python-requirements.txt").write_text("\n".join(sorted(pins, key=str.lower)) + "\n", encoding="utf-8")
assets = root / "app/src/main/assets"
assets.mkdir(exist_ok=True)
(assets / "THIRD_PARTY_NOTICES.txt").write_text("\n".join(sections), encoding="utf-8")
print(f"Recorded {len(pins)} pinned dependencies and license notices.")
