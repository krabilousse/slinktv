"""Exercise the APK's VOD resolver and media at distant offsets; never save signed URLs."""
import json
import re
import sys
from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import urljoin
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from resolver import resolve_video


def probe(video_id):
    stream = json.loads(resolve_video(video_id))
    if "error" in stream:
        raise RuntimeError(stream["error"])
    result = {"checked_at": datetime.now(timezone.utc).isoformat(), "video_id": video_id,
              "resolver_kind": stream["kind"], "qualities": [v["name"] for v in stream["variants"]], "decoded_frames": False}
    variant = next(v for v in reversed(stream["variants"]) if re.match(r"\d+p", v["name"]))
    with requests.Session() as client:
        client.headers.update(stream["headers"])
        response = client.get(variant["url"], timeout=20)
        response.raise_for_status()
        assert response.text.startswith("#EXTM3U")
        entries = [urljoin(response.url, line.strip()) for line in response.text.splitlines() if line and not line.startswith("#")]
        durations = [float(value) for value in re.findall(r"^#EXTINF:([0-9.]+)", response.text, re.M)]
        assert entries and len(entries) == len(durations)
        result.update(playlist_http=response.status_code, segments=len(entries), duration_seconds=round(sum(durations), 3), endlist="#EXT-X-ENDLIST" in response.text)
        result["media_samples"] = []
        for index in [0, len(entries) // 2]:
            with client.get(entries[index], stream=True, timeout=20) as media:
                media.raise_for_status()
                data = next(media.iter_content(188 * 100))
                ts = len(data) > 376 and data[0] == data[188] == data[376] == 0x47
                assert ts or b"ftyp" in data[:32] or b"styp" in data[:32], "Unrecognized media container"
                result["media_samples"].append({"offset_seconds": round(sum(durations[:index]), 3), "http": media.status_code, "bytes_sampled": len(data), "container": "MPEG-TS" if ts else "fMP4"})
    return result


if __name__ == "__main__":
    try:
        report = probe(sys.argv[1])
    except Exception as error:
        raise SystemExit("VOD check failed: " + type(error).__name__)
    print(json.dumps(report, indent=2))
