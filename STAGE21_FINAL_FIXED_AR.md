# Stage 21 Final Fixed — Stage 20 Smart SRT Reuse + OmniRoute

## Source baseline
Built from `SariSubtitle_Stage20_SmartSrtReuse_CompileFix.zip` and then added only the OmniRoute text-translation path/settings plus a defensive internal-engine guard.

## Preserved Stage 20 behavior
- SrtStorage normalizes X/Twitter URLs and uses the post Status ID as the cache identity.
- VoskModelManager and OfflineVoskTranscriber use the app's `filesDir/vosk-models/<model-id>` directory; `auto` selects the only installed language model if exactly one is installed.
- Audio/video picker uses `ACTION_OPEN_DOCUMENT` and supports `audio/*` and `video/*`.
- Audio files go to direct transcription; video files follow audio extraction/transcription flow.
- Existing cloud STT-to-Vosk fallback remains in place.
- Existing subtitle timing and Smart SRT Reuse implementation are not edited by the OmniRoute integration.

## OmniRoute addition
- Adds an optional Base URL (default `http://127.0.0.1:20128/v1`), model setting, optional encrypted bearer token, `/v1/models` connectivity test, and `/v1/chat/completions` text translation.
- Translation batches preserve cue ordering and timestamps; no audio is sent to OmniRoute.
- OmniRoute can be selected first or used as a fallback after Gemini fails.

## Internal engine guard
- The primary action routes through the internal flow.
- If no valid link exists, it offers the audio/video picker or SRT translation rather than opening SubWhisper.
- A second guard in `openPasteLinkFlow()` blocks SubWhisper when the internal engine is selected, including legacy/paste-link entry points.

## Validation performed
- ZIP integrity check.
- XML parse.
- Static assertions for Status ID matching, Vosk path/model selection, audio/video picker, Groq-to-Vosk fallback, OmniRoute endpoints, and internal-engine guard.
- Gradle build/device test not run in this environment; do not treat this archive as a confirmed APK build.
