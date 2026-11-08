"""Fetch a project-local Windows Android build toolchain."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tools"
TOOLS.mkdir(exist_ok=True)

def download(url, name, destination):
    archive = TOOLS / name
    if not archive.exists():
        print("Downloading", name, flush=True)
        urllib.request.urlretrieve(url, archive)
    if not destination.exists():
        destination.mkdir(parents=True)
        with zipfile.ZipFile(archive) as z:
            z.extractall(destination)
    print("Ready:", destination, flush=True)

if __name__ == "__main__":
    with urllib.request.urlopen("https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest") as r:
        release = json.load(r)
    jdk = next(a["browser_download_url"] for a in release["assets"]
               if "jdk_x64_windows_hotspot" in a["name"] and a["name"].endswith(".zip"))
    jobs = [
        (jdk, "jdk.zip", TOOLS / "jdk"),
        ("https://services.gradle.org/distributions/gradle-8.13-bin.zip", "gradle.zip", TOOLS / "gradle"),
        ("https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip", "android-cli.zip", TOOLS / "android-sdk" / "cmdline-tools" / "bootstrap"),
        ("https://www.nuget.org/api/v2/package/python/3.11.9", "python311.zip", TOOLS / "python311"),
    ]
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda args: download(*args), jobs))
