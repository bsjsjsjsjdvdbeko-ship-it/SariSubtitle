# Sari Subtitle — Stage 14a: STT Settings Repair

## Scope
This is Stage 14a, not the complete Stage 14 redesign. It prioritizes the requested STT controls while retaining all existing Kotlin files and the existing package `com.sari.subtitle`.

## Changes
- Added `SettingsSttActivity.kt` and `activity_settings_stt.xml`.
- Added the STT provider dropdown: Groq Whisper, Deepgram Nova-2, Vosk Offline.
- Added password inputs for Deepgram and OpenAI API keys.
- Added Deepgram/OpenAI connection tests and provider-key console links.
- Added a Vosk Arabic model download action using the existing `ModelDownloader`.
- Added encrypted-settings persistence fields for `sttProvider`, `deepgramKey`, and `openAiKey`.
- Wired the selected STT provider in `TranslationRepository`: Groq, Deepgram, or a downloaded Vosk model.
- OpenAI translation uses `openAiKey` when set, with the pre-existing generic translation key as fallback.
- Registered `SettingsSttActivity` in `AndroidManifest.xml`.
- Added an entry button from the existing settings screen.
- Added Arabic strings and an `ic_mic.xml` vector resource.

## Verification performed
- XML parsing: all XML files parsed successfully.
- ZIP integrity: checked after packaging.
- Original Kotlin files: retained; no existing Kotlin file was deleted.
- Android compilation: **لم أختبر**. This environment does not have a ready Android SDK/Gradle toolchain for an actual APK build.
- Runtime/network tests: **لم أختبر**.
- Vosk model download: the button points to the published Arabic MGB2 model archive, but the download was not run here.

## Important limitations
- This stage does not yet split all ten settings categories into their own screens and does not complete the requested home-screen redesign. Those belong to Stage 14b/14c/14d.
- The OpenAI key field is retained and persisted; OpenAI remains a translation provider in the current architecture, not an STT engine.
- A successful XML parse is not proof of a successful Android build.
