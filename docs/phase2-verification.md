# Phase 2 verification — 2026-11-08

After delivery, the owner reported that the prototype works and approved moving on to UI/UX design. The outstanding checks below record the agent's verification boundary at delivery; this report does not claim that every listed edge case was tested by the owner.

## Artifact

`dist/SlinkTV-0.2.0-debug.apk`, 81,927,234 bytes. Application ID `tv.slink.app`, versionCode 3, minimum API 34, target API 36. ARM 32-bit, ARM 64-bit, and x86_64 are packaged. Same debug signing key as the existing prototype, suitable for an update installation.

SHA-256: `5c197897e74672634568d0f5823c20a561a181f8d6fee72eb8af84250d19f97c`.

The existing local server serves the update at `http://192.168.1.218:8765/app.apk?v=0.2.0`. The complete HTTP download returned status 200, APK content type, and matching size and SHA-256. [Download evidence](evidence/download-0.2.0.json).

## Checks completed

- `scripts/build.ps1`: `testDebugUnitTest`, `assembleDebug`, `lintDebug`, and `assembleDebugAndroidTest` succeeded. Lint: **0 errors, 35 warnings**, including prototype text/localization, dependency-version, and landscape warnings.
- **28 Android host tests passed** under Robolectric. Eleven API tests cover scope/identity checks, pending/slow-down/denial/expiry, public-client refresh, encoded pagination cursors, empty lists, incomplete pagination, safe errors, and verification-URI restrictions. Five controller tests cover rotated-token persistence before API use, validation-401 recovery, revoked refresh, retry after network failure, and sign-out during a pending fetch without redisplaying late account data. Six login-screen tests cover activity creation, actual QR bitmap decoding, and all/live channel projections on API 34/35. Six existing player lifecycle/Back tests also pass.
- Real requests to Twitch with the supplied Client ID returned device HTTP 200, a Twitch verification URL, 1,800-second lifetime, and 5-second interval. One token poll after that interval returned HTTP 400 with `authorization_pending`, as expected before consent. No code, access token, or refresh token was logged or saved in the evidence. [Pre-consent evidence](evidence/device-flow-preconsent.json). Reproduce with `python scripts/verify-device-login.py <public-client-id>`.
- `scripts/package.ps1`: APK v2 signature and archive alignment passed. `aapt dump badging` confirmed the built version, SDK requirements, launcher activity, and ABIs.
- APK ZIP integrity, native-library inventory, Python archives, DEX, and notices checked. [Package inspection](evidence/apk-0.2.0-inspection.json). The previous native 16 KB-page limitation remains; this update does not claim to fix it.

The Python resolver and embedded player code are unchanged. The owner confirmed that phase 1 playback works on the Xiaomi box after the 0.1.1 update.

## Remaining real-device checks

Authenticated API responses in host tests are simulated. Phone consent, real followed-channel responses, refresh of a real granted token, and Android Keystore persistence have **not** been exercised on a TV/emulator by the agent. The instrumentation APK was compiled, not run. No account credentials were requested from the owner.

On the Xiaomi Mi Box:

1. Install 0.2.0 over the existing app. Open **Twitch account / QR login → Sign in with QR**.
2. Scan with a phone and approve on Twitch. Confirm the TV automatically transitions to the account data screen.
3. Check **All followed** against your actual follows, including offline channels, and **Live followed** against currently live channels. The app loads all pages; live status still reflects a changing API snapshot.
4. Inspect each **Raw JSON** entry. Profile, granted scopes, and every follows/live page should be readable using the remote. Credentials should not appear.
5. Back out and reopen the account screen; restart the app to check saved login. Try **Refresh**, cancellation of a new QR login, and **Sign out** followed by reopening the screen.
6. Confirm the existing public-stream player still opens and Back returns to SlinkTV.

The lists are diagnostic and are not yet playback shortcuts. The QR session authorizes Helix account data; it does not authenticate Streamlink playback or provide subscriber/ad-free playback benefits. See the source research for the distinction and the Xtra/Frosty comparisons.
