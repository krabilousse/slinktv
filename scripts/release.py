"""Package immutable release assets from a signed APK and the matching changelog."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--repository', default='krabilousse/slinktv')
    parser.add_argument('--check-history', action='store_true')
    args = parser.parse_args()
    gradle = Path('app/build.gradle').read_text(encoding='utf-8')
    version = re.search(r"versionName '([^']+)'", gradle)[1]
    code = int(re.search(r'versionCode (\d+)', gradle)[1])
    if args.tag != 'v' + version:
        raise SystemExit('Tag must match app versionName')
    changelog = Path('CHANGELOG.md').read_text(encoding='utf-8')
    match = re.search(r'^## \[' + re.escape(version) + r'\][^\n]*\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if not match or not match[1].strip():
        raise SystemExit('A changelog entry is required')
    sdk = Path(os.environ.get('ANDROID_HOME', '.tools/android-sdk'))
    aapt = sdk / 'build-tools/35.0.0' / ('aapt.exe' if os.name == 'nt' else 'aapt')
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(args.apk)], text=True)
    if f"name='tv.slink.app' versionCode='{code}' versionName='{version}'" not in badging:
        raise SystemExit('APK identity/version does not match source')
    if args.check_history:
        releases = json.loads(subprocess.check_output(['gh', 'api', f'repos/{args.repository}/releases?per_page=100'], text=True))
        for release in releases:
            if release['draft'] or release['tag_name'] == args.tag:
                continue
            asset = next((a for a in release['assets'] if a['name'] == 'update.json'), None)
            if asset:
                previous = json.loads(subprocess.check_output(['gh', 'api', asset['url'], '-H', 'Accept: application/octet-stream'], text=True))
                if code <= int(previous['versionCode']):
                    raise SystemExit('versionCode must exceed every published release, including previews')
    dist = Path('dist'); dist.mkdir(exist_ok=True)
    name = f'SlinkTV-{version}.apk'
    apk = dist / name
    shutil.copyfile(args.apk, apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    notes = match[1].strip() + '\n'
    metadata = dict(schemaVersion=1, packageName='tv.slink.app', versionCode=code, versionName=version,
                    minSdk=34, channel='preview' if '-' in version else 'stable', apkName=name,
                    apkUrl=f'https://github.com/{args.repository}/releases/download/{args.tag}/{name}',
                    size=apk.stat().st_size, sha256=digest, notes=notes)
    (dist / 'update.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    (dist / 'release-notes.md').write_text(notes, encoding='utf-8')
    (dist / 'SHA256SUMS.txt').write_text(f'{digest}  {name}\n', encoding='ascii')
    shutil.copyfile(apk, dist / 'app.apk')
    print(f'Packaged {name}: {apk.stat().st_size} bytes, SHA-256 {digest}')


if __name__ == '__main__':
    main()
