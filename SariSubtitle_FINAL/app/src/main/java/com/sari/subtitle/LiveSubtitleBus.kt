package com.sari.subtitle

/**
 * Contract between LiveCaptureService and the foreground UI.
 * No audio, API key, or full transcript buffer is carried by the broadcast.
 */
object LiveSubtitleBus {
    const val ACTION_CUE = "com.sari.subtitle.action.LIVE_CUE"
    const val ACTION_STATUS = "com.sari.subtitle.action.LIVE_STATUS"

    const val EXTRA_SEQUENCE = "sequence"
    const val EXTRA_START_MS = "start_ms"
    const val EXTRA_END_MS = "end_ms"
    const val EXTRA_TEXT = "text"
    const val EXTRA_LANGUAGE = "language"
    const val EXTRA_PROVIDER = "provider"
    const val EXTRA_STT_PROVIDER = "stt_provider"
    const val EXTRA_TRANSLATION_PROVIDER = "translation_provider"
    const val EXTRA_IS_FINAL = "is_final"
    const val EXTRA_REVISION = "revision"
    const val EXTRA_CAPTURE_STARTED_ELAPSED_MS = "capture_started_elapsed_ms"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_DISPLAY_UNTIL_MS = "display_until_ms"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DETAIL = "detail"
}
