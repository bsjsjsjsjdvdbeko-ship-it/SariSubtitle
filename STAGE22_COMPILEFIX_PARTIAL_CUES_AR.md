# Stage 22 CompileFix — Partial SRT Cue Translation

## Changes
- `translateAuto()` no longer aborts when translated SRT cue count differs from the input count.
- Translated cues are matched to their original cues by timestamp, not by list position. This prevents a missing cue from shifting text onto the wrong timestamp.
- Original cue numbering and timestamps are always taken from the source SRT.
- Missing/empty translations are retried individually. If a cue still fails, its original text and timing remain and processing continues.
- Extra provider-returned cues are ignored and logged.
- Added `SrtTranslationService` logs for bulk failures, cue recovery, per-cue failures, missing/extra cues, and retained originals.
- Existing OmniRoute/Gemini provider selection and fallback behavior are preserved.

## Honest verification status
- ZIP archive integrity was checked after writing.
- Source-level checks and Android manifest XML parsing were performed.
- No Gradle wrapper / usable Android SDK build environment was available in this session, so this ZIP has **not** been verified by a full Gradle build, APK install, or device test.
- Gemini/OmniRoute API keys and network access were not live-tested.

## Runtime diagnosis
Use Logcat filter `SrtTranslationService`. Look for messages such as `Bulk translation failed`, `Partial SRT response`, `Translation failed for cue`, or `was not translated`. A cue that cannot be translated remains in the SRT with its original timestamp instead of stopping the whole file.
