# SlinkTV 0.3.1: TV controls and playback quality

## Behavior

- Opening a card overlays an indeterminate spinner on its thumbnail. Card dimensions and focus are retained. Back, navigation to another screen/feed, and backgrounding cancel the pending launch; its late response is ignored. Manual channel entry uses a small inline spinner because there is no card. Error messages remain actionable dialogs.
- Portrait variants are removed both during Streamlink extraction and when the player accepts a stream response. They cannot become the initial choice or appear in the quality dialog.
- Quality labels use resolution and frame rate, for example `1080P · 60 FPS (best)`, `720P · 60 FPS`, and `Audio only`.
- Settings saves a device-wide default: Best available or a resolution preference. Missing resolutions fall back to the best available rendition below the preference. If every video rendition exceeds it, the lowest available video resolution is used; the player does not unexpectedly switch to audio only. Higher frame rate wins within a resolution. Manual player changes do not overwrite the default.
- Feed and sort controls are separate two-option pill groups. Activation uses OK; moving focus alone does not switch feeds or sorting. Logged-out Popular, signed-in Following, and relevance opt-in behavior are preserved.
- Navigation tabs have subdued selected surfaces; focus uses a bright contrasting surface. General action buttons have restrained 6dp corners and 10dp separation. Pill groups have 24dp separation. Language is aligned separately with an additional gap.
- The header has no Browse or More button. Existing scroll-triggered pagination remains; short pages also fetch the next page when the viewport cannot scroll. A failed page keeps loaded cards and exposes Retry or Sign in. Paging stops retrying automatically after a failure.
- Settings rows have consistent backgrounds, spacing, and separate Default quality and Player test actions.

## Design sources

Android TV's [focus system](https://developer.android.com/design/ui/tv/guides/styles/focus-system) distinguishes focused, pressed, and selected states and permits grouping related controls. The implementation uses those distinctions so the current feed remains identifiable while another option has remote focus.

Android TV's [layout guidance](https://developer.android.com/design/ui/tv/guides/styles/layouts) recommends designing at a 960 × 540dp reference size, clear horizontal/vertical navigation, and sufficient spacing to prevent controls overlapping. This revision preserves two complete card rows at the reference TV size and groups related choices while separating unrelated actions. It retains the existing compact screen margins rather than claiming full adherence to the guide's suggested overscan margin.

## Verification

Completed on 2026-11-08: 70 JVM/Robolectric tests and 22 Python tests passed. APK and instrumentation APK assembly passed. Lint completed with zero errors and 33 warnings. APK signature and ZIP alignment checks passed. The LAN download's full SHA-256 matches the packaged 0.3.1 APK (version code 5, 84,240,020 bytes). Evidence: [JVM results](evidence/jvm-tests-0.3.1.json), [APK inspection](evidence/apk-0.3.1-inspection.json), [download hash verification](evidence/download-0.3.1.json), [package identity](evidence/apk-0.3.1-badging.txt).

A live host check using the updated resolver returned Linkus7's `1080p60`, `720p60`, `480p30`, `360p30`, `160p30`, and `audio_only` variants without errors. This verifies extraction, not TV decoding.

Host checks cover portrait exclusion, highest-quality ranking, missing-resolution fallback, readable labels, saved settings, initial player selection, manual quality changes, and unchanged Back behavior. Card tests cover spinner recycling and cancellation with a late successful response. Layout checks cover fixed skeleton/card bounds, two visible rows, button text fit, pill separation, and remote focus traversal.

The native Robolectric renderer produces `app/build/reports/ui/stream-wall-0.3.1.png`; this is an Android view render with fixture state, not a screenshot from the Xiaomi TV. The test configures remote input mode before attaching the window. Labels are single-line and bounded to their controls, without a horizontally scrolling text layout.

The release audit records APK identity, signing/alignment verification, test results, and a full hash comparison of the APK downloaded from the existing LAN server. Physical-TV decoding, visual comfort, and remote feel remain owner acceptance checks. Existing native 16KB-page compatibility and unofficial guest-directory limitations are unchanged; see the README.

On the Xiaomi box: install over 0.3.0, open Linkus7, confirm the card spinner and landscape playback, inspect quality labels, set 720P in Settings, then reopen a stream. Test both pills with Left/Right/OK and Back from playback. Scroll to the bottom of Popular to check pagination or its Sign in/Retry recovery.
