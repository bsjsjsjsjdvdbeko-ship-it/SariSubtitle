# Stage 13 Unified Audit

This package is a code-level stabilization of the existing Stage 13 project.

Verified statically:
- package remains com.sari.subtitle
- existing files were preserved
- SettingsRepository contains OmniRoute and orchestration flags
- SettingsActivity binds the new orchestration switches
- OpenAI translation adapter exists
- Vosk Android dependency is declared
- GitHub Actions workflow uploads app/build/outputs/apk/debug/app-debug.apk
- no parcelableIntentExtra reference
- no app:valueFrom/valueTo/stepSize reference
- exactly one TranscriptDeduplicator class
- VideoPlayerActivity uses C.SELECTION_FLAG_DEFAULT
- OverlayService does not reference MaterialCardView
- BrowserActivity assigns a real WebViewClient

Not verified in this environment:
- Gradle Android build
- APK installation/runtime
- real provider requests
- WER/CER benchmark
- real Vosk Arabic model availability
- GitHub Actions execution
