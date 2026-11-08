"""Live network check using the same resolver as the APK, without persisting signed URLs."""
import json
from pathlib import Path
import sys
from urllib.parse import urljoin
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from resolver import resolve, DEMO_URL


def check_hls(url, headers):
    with requests.Session() as session:
        session.headers.update(headers)
        for _ in range(4):
            response = session.get(url, timeout=20)
            response.raise_for_status()
            assert response.text.startswith("#EXTM3U"), "Not an HLS playlist"
            entries = [line.strip() for line in response.text.splitlines() if line and not line.startswith("#")]
            assert entries, "Empty playlist"
            next_url = urljoin(response.url, entries[0])
            if "#EXT-X-STREAM-INF" in response.text:
                url = next_url
                continue
            with session.get(next_url, timeout=20, stream=True) as segment:
                segment.raise_for_status()
                data = next(segment.iter_content(188 * 100))
                assert len(data) >= 188 * 3, "Empty media segment"
                ts = data[0] == 0x47 and data[188] == 0x47 and data[376] == 0x47
                mp4 = b"ftyp" in data[:32] or b"styp" in data[:32] or b"moof" in data[:64]
                assert ts or mp4, "Unexpected segment container"
                return {"playlist_http": response.status_code, "segment_http": segment.status_code,
                        "sample_bytes": len(data), "container": "MPEG-TS" if ts else "fMP4"}
        raise AssertionError("Too many nested playlists")


if __name__ == "__main__":
    result = json.loads(resolve(sys.argv[1] if len(sys.argv) > 1 else "Linkus7"))
    if "error" in result:
        raise SystemExit(result["error"])
    report = {"channel": result["channel"], "qualities": []}
    for variant in result["variants"]:
        report["qualities"].append({"name": variant["name"], **check_hls(variant["url"], result["headers"])})
    report["player_test"] = check_hls(DEMO_URL, {})
    print(json.dumps(report, indent=2))
