# Sari Subtitle — Stage 8

Stage 8 is an in-place extension of the supplied Stage 7 Android source project. The existing
applicationId (`com.sari.subtitle`), namespace, Kotlin/Gradle versions, Room database, capture
service, overlay service and settings architecture were preserved.

## What was implemented

- Internal HTTPS WebView browser with URL bar, back/forward/refresh, progress/error states and external-browser fallback.
- Secure WebView defaults: no file/content access, no mixed content, no JavaScript native bridge, and no automatic permission grants.
- Platform URL resolver for YouTube, TikTok, Instagram/Reels, Facebook/Watch, X, Vimeo, Twitch and direct media URLs.
- Android `ACTION_SEND` text/URL and `ACTION_VIEW` HTTP(S) link handling with `singleTop`/`onNewIntent` duplicate protection.
- Direct-media playback through the existing Media3/ExoPlayer screen, including HLS/DASH modules.
- Optional remote SRT/WebVTT subtitle track support for direct media URLs.
- Official Widevine DRM playback configuration: the user may supply an HTTPS license URL from an authorized content provider; no DRM bypass, key extraction, or protected-media scraping is performed.
- A live subtitle state bus connected to the existing capture/translation pipeline so translated results can appear inside the video player as well as the existing overlay.
- MediaProjection stop callback and existing coroutine cancellation/resource cleanup retained.
- SRT/WebVTT parsing utility with automated JVM tests.
- No API keys are included.

## Platform policy / capability boundary

A normal webpage URL is not treated as a direct media file. Known social/video platforms are opened in the internal browser rather than scraped for protected media. The application does not bypass login, DRM, geographic restrictions, signed URLs, anti-bot controls or platform access rules. DRM playback uses Media3's official Widevine path only when an authorized license endpoint is supplied.

Playback capture remains subject to Android's `AudioPlaybackCapture` rules and the source application's capture policy. If the source does not permit capture, Sari Subtitle reports failure rather than attempting a bypass.

## Groq

Stage 8 keeps Groq's official speech-to-text endpoint and `whisper-large-v3-turbo`. Current Groq documentation lists it as multilingual and supports `json`, `text`, and `verbose_json`; segment/word timestamps require `verbose_json`. The current live path remains low-latency chunk transcription, while precise player synchronization is only available for subtitle sources that provide actual timestamps.

## Configuration

1. Open the project in Android Studio with JDK 17 and Android SDK 35.
2. Add the user's Groq API key in Settings. Never commit it.
3. Select the translation provider and configure its key/email only when required.
4. Grant MediaProjection consent when starting live playback capture.
5. Grant overlay permission only if floating captions are wanted.

## External services

- Groq Speech-to-Text: required for live transcription.
- Google Cloud Translation: optional.
- MyMemory: optional.
- Network access is HTTPS only.

## Verification

The source tree and XML were inspected after modification. Automated JVM tests were added for subtitle parsing and platform URL resolution. This environment did not contain Gradle/Android SDK tooling and could not resolve external Gradle downloads, so `assembleDebug`, Gradle Sync, and device/end-to-end tests were **not claimed as executed successfully**.

## Known limitations

- Social platform webpage playback depends on the platform's own WebView compatibility and policies; a page URL is not converted into a protected media URL.
- Some platforms may require their official application or external browser.
- Playback capture can return no audio when the source application disallows capture or uses protected playback.
- The live transcription path does not invent frame-accurate timestamps. It uses arrival/capture timing for live display; exact synchronization is only guaranteed when a real subtitle track supplies timestamps.
- Microphone fallback is not silently enabled; the current live path intentionally remains playback-capture based.

## DRM الرسمي

للمحتوى المحمي يمكن إدخال رابط ترخيص Widevine الرسمي عبر HTTPS في شاشة المشغل. التطبيق لا يستخرج مفاتيح DRM ولا يتجاوز الحماية. نجاح تشغيل DRM لا يعني أن `AudioPlaybackCapture` مسموح به؛ إذا منع المصدر التقاط الصوت، تبقى الترجمة الحية غير متاحة لهذا المصدر.

## Stage 9 — Context-Aware Translation

Stage 9 upgrades live translation from independent audio chunks to a context-aware pipeline:

- 2-second audio windows with 400 ms overlap to reduce boundary word loss.
- Timestamp-aware STT using Groq `verbose_json` segment timestamps when returned.
- Limited in-session context window for previous subtitles.
- User-editable terminology glossary (`English term = Arabic term`).
- Lightweight speaker-label memory when transcripts contain `Name:` labels; this is not automatic identity recognition.
- Local Room Translation Memory to avoid translating identical text repeatedly.
- Bounded audio processing queue to prevent an ever-growing backlog when the network is slow.
- Context-aware Groq translation uses the currently supported `openai/gpt-oss-20b` model; it does not use the deprecated `llama-3.3-70b-versatile` model.
- Google/MyMemory remain available as existing fallback providers, but their APIs do not receive the same LLM context prompt.

Stage 9 does not bypass DRM, platform restrictions, login requirements, or Android playback-capture restrictions. It also does not claim human-level translation quality or a fixed end-to-end latency.


## Stage 10 — Human-Quality Translation Engine

Stage 10 upgrades the translation layer from a single AI pass to a subtitle-editor workflow when Groq + **Human Quality** is selected:

- **Draft pass:** translates with dialogue-aware context, glossary, speaker/name notes, tone and subtitle concision rules.
- **Senior-review pass:** reviews the original + draft and fixes literal wording, pronouns, terminology drift, tone, omissions/additions and unnatural Arabic phrasing.
- **Paired episode context:** the rolling context now remembers recent source + translated dialogue, not source text alone.
- **Terminology consistency:** user glossary remains mandatory when applicable.
- **Automatic speech-language mode:** new installations default STT language to `auto`, allowing Whisper to detect the spoken language instead of forcing Arabic.
- **Quality selector:** Fast / Balanced / Human Quality. Human Quality costs an extra translation request and can increase latency.

This is designed to move the output substantially closer to professional subtitle practice, but it cannot guarantee human parity: a real human still has access to the complete episode, visual context, cultural knowledge, character identity, and final editorial judgment.


## Stage 11 — Live Subtitle Quality & Sync

Stage 11 focuses on the actual use case: arbitrary short videos and live playback rather than pre-made SRT files.

- Professional subtitle-style overlay: bottom-centered, transparent background, white bold text, shadow, two-line limit, and no header/card.
- Overlay is non-touchable so it does not steal taps from the video player.
- Live results carry capture timestamps and measured pipeline latency.
- Segment timestamps from Whisper are used to estimate cue duration instead of always showing every cue for a fixed 2 seconds.
- Latest-wins audio queue keeps the live pipeline bounded instead of building a multi-second backlog.
- Old network results are rejected when a newer capture window is already available.
- Human Quality uses current Groq GPT-OSS 120B in one editorial pass; Balanced/Fast use GPT-OSS 20B.
- Human Quality uses Whisper Large V3 with a context prompt; other modes use Whisper Large V3 Turbo.
- Whisper prompt carries terminology and recent context to improve names/terms.

### Important synchronization limitation

For an arbitrary external Android app, Sari Subtitle can timestamp the captured audio with `elapsedRealtime`, but it cannot read that other app's exact video playback position. Therefore external-app synchronization cannot be mathematically exact. The Stage 11 design reduces latency and prevents stale/out-of-order subtitles; exact timeline synchronization is only possible when the app controls the video player's clock.

The Stage 11 implementation does not bypass DRM, content protection, or another app's capture restrictions.


## Stage 12 — OmniRoute Multi-Provider AI Orchestration

Stage 12 extends Stage 11 in-place. The Android app keeps the existing capture, overlay, subtitle timing, translation memory and context pipeline, while OmniRoute becomes an optional external AI gateway.

### Real integration

When **OmniRoute — Multi-Provider** is selected in Settings:

```text
Android playback capture
        ↓
Sari Subtitle
        ↓
OmniRoute /v1
   ┌────┴─────────────┐
   ↓                  ↓
STT provider pool   Translation model/combo
   ↓                  ↓
Transcript           Arabic translation
        └──────┬──────┘
               ↓
        Live Subtitle Overlay
```

The app uses the OpenAI-compatible OmniRoute endpoints:
- `POST /v1/audio/transcriptions` for STT.
- `POST /v1/chat/completions` for translation.
- `GET /v1/models` for the connection/health test.

The STT model field accepts an ordered comma-separated list. The default is `deepgram/nova-3,assemblyai/best`; Sari tries the next model if the previous request fails. OmniRoute can additionally perform its own server-side combo/fallback and quota-aware routing when configured on the server.

### Security

- OmniRoute API keys are stored in the existing encrypted settings store.
- No provider keys are hard-coded.
- The app does not bundle provider credentials.
- Use an HTTPS OmniRoute endpoint for a deployed server.
- Local HTTP development is intentionally not enabled by default because the existing network security policy rejects cleartext traffic.

### Stage 12 limitations

The Android application does not embed the OmniRoute server process. OmniRoute is a server-side gateway and must be running on a reachable machine/server. This avoids shipping a Node/Next.js gateway inside the Android APK and keeps the mobile client lightweight.

### Verification status

The Stage 11 source was modified directly; no new Android project was created. The new OmniRoute client, settings persistence, provider selection, STT fallback list, translation routing, and connection test were added. This environment does not contain the full Android SDK/Gradle wrapper, so a successful `assembleDebug` or device test is **not claimed**. XML parsing and source-level consistency checks are performed before packaging.
