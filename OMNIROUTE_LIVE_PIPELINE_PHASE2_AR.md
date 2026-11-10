# OmniRoute Live Pipeline – Phase 2 hardening

## Changes in this phase
1. Fixed provider identity mapping in `LiveSttRouter.healthId`: OmniRoute STT now maps to `ProviderConnectionTester.OMNIROUTE` rather than incorrectly sharing Groq's health/metrics identity.
2. Added OmniRoute to live STT quality metrics in `LiveCaptureService.recordSttQuality`.
3. Added an explicit runtime status event when the selected provider is OmniRoute and the capture service uses its HTTP chunked live path.

## Important protocol distinction
This is **near-live chunked transcription**, not a persistent WebSocket/SSE transcription session. The app captures PCM continuously, chunks it, and submits each chunk to the OpenAI-compatible `/v1/audio/transcriptions` endpoint. The endpoint used by `OmniRouteWhisperService` returns a completed transcription response; the client cannot produce genuine partial tokens from that response. Genuine streaming requires a provider/model-specific streaming protocol supported by that provider. Deepgram remains the only existing persistent WebSocket STT path in this project.

## Validation
- Source-level checks performed for the exact provider identity and metrics mappings.
- ZIP archive integrity checked.
- Android Gradle build and device test were not run in this environment. Re-run the project's GitHub Actions build before installing.
