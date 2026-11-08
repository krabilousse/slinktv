"""Serve only the prototype APK to the home network."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import shutil
from urllib.parse import urlsplit

APK = Path(__file__).resolve().parents[1] / "dist/app.apk"


class DownloadHandler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.download(False)

    def do_GET(self):
        self.download(True)

    def download(self, include_body):
        if urlsplit(self.path).path not in ("/", "/app.apk"):
            self.send_error(404)
            return
        with APK.open("rb") as source:
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(APK.stat().st_size))
            self.send_header("Content-Disposition", 'attachment; filename="SlinkTV.apk"')
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if include_body:
                try:
                    shutil.copyfileobj(source, self.wfile)
                except (BrokenPipeError, ConnectionResetError):
                    pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not APK.is_file():
        raise SystemExit(f"APK not found: {APK}")
    print(f"Serving {APK.name} at http://{args.bind}:{args.port}/app.apk", flush=True)
    ThreadingHTTPServer((args.bind, args.port), DownloadHandler).serve_forever()
