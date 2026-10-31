package com.sari.subtitle

/**
 * Stage 41: deterministic PCM16 chunking for live STT.
 * Input is mono PCM16 little-endian. No audio is persisted by this class.
 */
class AudioChunker(
    private val sampleRateHz: Int = 16_000,
    private val channels: Int = 1,
    private var chunkDurationMs: Long = 2_000L,
    private var overlapMs: Long = 250L
) {
    data class Chunk(val sequence: Long, val startMs: Long, val durationMs: Long, val pcm: ByteArray)

    init {
        require(sampleRateHz in 8_000..48_000) { "sampleRateHz out of range" }
        require(channels == 1) { "Stage 41 requires mono PCM16 input" }
        require(chunkDurationMs in 500L..10_000L) { "chunkDurationMs out of range" }
        require(overlapMs in 0L until chunkDurationMs) { "overlapMs out of range" }
    }

    private val bytesPerMs = sampleRateHz * channels * 2L / 1000L
    private var chunkBytes = (chunkDurationMs * bytesPerMs).toInt()
    private var overlapBytes = (overlapMs * bytesPerMs).toInt()
    private var stepBytes = chunkBytes - overlapBytes
    private var nextSequence = 0L
    private var absoluteOffsetBytes = 0L
    private var buffer = ByteArray(0)

    @Synchronized
    fun append(pcm16le: ByteArray, size: Int = pcm16le.size): List<Chunk> {
        require(size in 0..pcm16le.size)
        if (size == 0) return emptyList()
        buffer += pcm16le.copyOf(size)
        val out = mutableListOf<Chunk>()
        while (buffer.size >= chunkBytes) {
            val chunkPcm = buffer.copyOfRange(0, chunkBytes)
            val startMs = (absoluteOffsetBytes * 1000L) / (sampleRateHz * channels * 2L)
            out += Chunk(nextSequence++, startMs, chunkDurationMs, chunkPcm)
            buffer = buffer.copyOfRange(stepBytes, buffer.size)
            absoluteOffsetBytes += stepBytes
        }
        return out
    }

    @Synchronized
    fun flush(): Chunk? {
        if (buffer.size < 2) return null
        val aligned = buffer.size - (buffer.size % 2)
        if (aligned <= 0) return null
        val startMs = (absoluteOffsetBytes * 1000L) / (sampleRateHz * channels * 2L)
        val duration = (aligned * 1000L) / (sampleRateHz * channels * 2L)
        val result = Chunk(nextSequence++, startMs, duration, buffer.copyOf(aligned))
        absoluteOffsetBytes += aligned
        buffer = ByteArray(0)
        return result
    }

    @Synchronized
    fun reconfigure(newChunkDurationMs: Long, newOverlapMs: Long = minOf(overlapMs, newChunkDurationMs / 8L)) {
        require(newChunkDurationMs in 500L..10_000L) { "chunkDurationMs out of range" }
        require(newOverlapMs in 0L until newChunkDurationMs) { "overlapMs out of range" }
        chunkDurationMs = newChunkDurationMs
        overlapMs = newOverlapMs
        chunkBytes = (chunkDurationMs * bytesPerMs).toInt()
        overlapBytes = (overlapMs * bytesPerMs).toInt()
        stepBytes = chunkBytes - overlapBytes
    }

    fun reset() {
        synchronized(this) {
            buffer = ByteArray(0)
            absoluteOffsetBytes = 0L
            nextSequence = 0L
        }
    }
}
