# Phase 1 verification — 2026-11-08

The owner subsequently confirmed that playback works after installing the 0.1.1 fix. The remaining retest wording below records what was outstanding at delivery. Phase 2 evidence is tracked separately in [phase2-verification.md](phase2-verification.md).

## Version 0.1.1 — player launch fix

The owner confirmed that the installed 0.1.0 APK resolves Linkus7 and lists qualities on the Xiaomi box, but closes when either a selected quality or the independent test stream opens.

Reproduced with `scripts/build.ps1 -Tasks testDebugUnitTest`, using Robolectric's Android 14 activity lifecycle and the real `PlayerActivity.onCreate`. Before the fix, `selectingAStreamCreatesThePlayerScreen` failed with:

```text
NullPointerException: Cannot invoke DecorView.getWindowInsetsController()
because this.mDecor is null
  at PhoneWindow.getInsetsController
  at PlayerActivity.onCreate (original line 69)
```

The exception happens before Media3 or any network load. Fullscreen setup now follows `setContentView`, which creates the window decor. Back uses AndroidX Activity's lifecycle-aware dispatcher and finishes only the player activity; the existing channel activity remains underneath it. One Back action returns to channel entry even while playback controls are visible. A quality dialog consumes Back to dismiss the dialog first.

Final verification for 0.1.1:

- **6 Robolectric cases passed**: player-screen creation, one Back action closing the player, and player attachment/release, each on API 34 and 35. These execute the actual activity lifecycle; they do not decode network media.
- App and instrumentation APKs built; lint **0 errors, 32 warnings**.
- Signature and archive alignment verified. Built manifest reports version 0.1.1 / versionCode 2, minimum API 34, target API 36.
- Downloaded the complete updated APK from `http://192.168.1.218:8765/app.apk?v=0.1.1`; its SHA-256 matched the packaged artifact.
- `dist/SlinkTV-0.1.1-debug.apk`: 90,245,428 bytes; SHA-256 `c55f945a41a155bbff9de8d57ad7f6f02302a15e41a33a5989b599f9a2763c6c`. [Native/package inspection](evidence/apk-0.1.1-inspection.json).

Retest video/audio on the Mi Box after updating. Android device instrumentation still has not run, and the native 16 KB dependency limitation remains. The previously tested Python resolver is unchanged.

The sections below preserve the original 0.1.0 build evidence.

## Delivered artifact

`dist/SlinkTV-0.1.0-debug.apk`, 92,227,891 bytes, application ID `tv.slink.app`, version 0.1.0 (1). Debug-signed for sideloading. SHA-256:

```text
e82ee930d4e69d180e08c7200aeb05d3ce7d098d52e98a248d3b87990df933a0
```

API 34 minimum / API 36 target. ARM 32-bit, ARM 64-bit, and x86_64 are packaged. Android TV launcher category, banner, Internet permission, and no touchscreen requirement were verified from the built APK with `aapt dump badging`.

## Checks completed

- `scripts/build.ps1`: `assembleDebug`, `lintDebug`, and `assembleDebugAndroidTest` succeeded. Final lint result: **0 errors, 30 warnings**. Warnings cover untranslated prototype text, newer dependency versions, and fixed landscape orientation. Full reports remain in `app/build/reports/`.
- `pytest tests -q`: **16 passed**, including channel normalization, URL rejection before network access, quality sorting/alias removal, offline handling, session cleanup, and avoiding signed URL disclosure on failures.
- Same Python resolver used by the app, running on the Windows host: **Linkus7 resolved** to 1080p60, 720p60, 480p30, 360p30, 160p30, and audio_only. Every rendition's playlist and sampled media segment returned HTTP 200 and recognizable MPEG-TS bytes. The independent Mux HLS sample passed the same check. [Recorded network evidence](evidence/linkus7-network.json). This checks container bytes, not decoded video/audio.
- `apksigner verify --verbose`: valid APK Signature Scheme v2 signature, one signer.
- `zipalign -c -P 16 4`: passed archive alignment. This is **not** sufficient to establish native 16 KB compatibility.
- APK ZIP integrity, three expected ABIs, embedded Python archives, license asset, and application bytecode verified. [APK inspection](evidence/apk-inspection.json).
- Native ELF load segments inspected recursively inside Chaquopy archives. libxml2, libxslt, and libexslt still have 4 KB alignment, including ARM64. This artifact must be treated as **4 KB-page-device only**; rebuild/update those dependencies before claiming native 16 KB support. Chaquopy's 32-bit bridge also has 4 KB alignment.

## Not verified

The environment's device integration reported that agent device access is disabled. No TV/emulator was installed to, launched, or controlled. Therefore **embedded Python startup, actual video/audio playback, D-pad usability, and lifecycle behavior are unverified on Android**. Passing the host tests and producing an APK do not close the primary on-device playback acceptance gate.

The companion test APK is `dist/SlinkTV-0.1.0-tests.apk`. Instrumentation tests compile but have **not run**. They check imports of Streamlink/lxml/Crypto inside Android and Media3's first decoded frame from a network HLS sample. With an authorized connected device, run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Mi Box acceptance checklist

1. Confirm Android 14 or newer; install and launch from the TV home screen.
2. Select **Test player**. Confirm moving video and audible sound; test Pause/Play and Back using only the remote.
3. With Linkus7 live, select **Open channel → 720p60**, then **1080p60**. Confirm continuous video/audio for at least ten minutes.
4. Change qualities, pause/resume, and select Go live. Verify focus remains visible and every action can be reached by D-pad.
5. Observe a Twitch ad transition if available. Ads are not filtered by this resolver-only integration.
6. Enter an offline channel and an invalid URL. Verify an actionable message and that another channel can be opened.
7. Disconnect/reconnect the network; try Retry. If the URL expires, use Channel → Open channel.
8. Press Home during playback; verify audio stops. Return, then Back out; verify no leaked audio or frozen player.
9. Before broader Android 15/16 distribution, rebuild/audit native XML dependencies and test on a 16 KB image/device.

## Phase 2 conclusion

Phone/QR login for Helix account access and followed-stream listing is feasible through Twitch's official public-client device flow; it needs this app's own registered client ID. No TV browser is necessary for that account-consent flow. Authenticated playback and integrity challenges remain separate investigations. See primary-source research.
