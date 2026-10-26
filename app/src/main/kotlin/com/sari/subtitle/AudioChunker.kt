package com.sari.subtitle

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max

/**
 * Low-latency overlapping windows. The window is intentionally short enough for live use;
 * longer 10-second windows would increase synchronization lag rather than improve it.
 */
class AudioChunker(context: android.content.Context, private val sampleRate: Int = 16_000) {
    private val chunkSamples = sampleRate * CHUNK_MS.toInt() / 1000
    private val overlapSamples = sampleRate * OVERLAP_MS.toInt() / 1000
    private val pending = ArrayDeque<AudioChunk>()
    private val buffer = ShortArray(chunkSamples)
    private var count = 0
    private var windowStartElapsedMs = -1L
    private var sequence = 0L
    private val outputDir = File(context.cacheDir, "audio_chunks").apply { mkdirs() }

    @Synchronized
    fun addSamples(samples: ShortArray, count: Int) {
        val safeCount = count.coerceIn(0, samples.size)
        if (safeCount == 0) return
        if (windowStartElapsedMs < 0) windowStartElapsedMs = SystemClock.elapsedRealtime()
        var offset = 0
        while (offset < safeCount) {
            val copied = minOf(chunkSamples - this.count, safeCount - offset)
            System.arraycopy(samples, offset, buffer, this.count, copied)
            this.count += copied
            offset += copied
            if (this.count == chunkSamples) {
                val start = windowStartElapsedMs
                val chunkFile = File(outputDir, "chunk_${System.nanoTime()}.wav")
                writeWav(chunkFile, buffer, this.count)
                var energy = 0.0
                for (i in 0 until this.count) {
                    val sample = buffer[i].toDouble()
                    energy += sample * sample
                }
                val rms = kotlin.math.sqrt(energy / this.count)
                if (rms >= RMS_THRESHOLD) {
                    pending.addLast(AudioChunk(chunkFile, start, CHUNK_MS, ++sequence))
                } else chunkFile.delete()

                System.arraycopy(buffer, chunkSamples - overlapSamples, buffer, 0, overlapSamples)
                this.count = overlapSamples
                windowStartElapsedMs = start + STEP_MS
            }
        }
    }

    @Synchronized fun hasChunkReady(): Boolean = pending.isNotEmpty()

    @Synchronized fun getNextChunkInfo(): AudioChunk {
        check(pending.isNotEmpty()) { "No complete audio chunk is ready." }
        return pending.removeFirst()
    }

    @Synchronized fun getNextChunk(): File = getNextChunkInfo().file

    @Synchronized fun clear() {
        while (pending.isNotEmpty()) pending.removeFirst().file.delete()
        outputDir.listFiles()?.forEach { it.delete() }
        count = 0
        windowStartElapsedMs = -1L
        sequence = 0L
    }

    private fun writeWav(file: File, samples: ShortArray, sampleCount: Int) {
        val dataBytes = sampleCount * 2
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII)); writeIntLE(out, 36 + dataBytes)
            out.write("WAVE".toByteArray(Charsets.US_ASCII)); out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16); writeShortLE(out, 1); writeShortLE(out, 1)
            writeIntLE(out, sampleRate); writeIntLE(out, sampleRate * 2); writeShortLE(out, 2); writeShortLE(out, 16)
            out.write("data".toByteArray(Charsets.US_ASCII)); writeIntLE(out, dataBytes)
            for (i in 0 until sampleCount) writeShortLE(out, samples[i].toInt())
        }
    }
    private fun writeIntLE(out: FileOutputStream, value: Int) {
        out.write(value and 0xff); out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff); out.write((value ushr 24) and 0xff)
    }
    private fun writeShortLE(out: FileOutputStream, value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff) }

    companion object {
        const val CHUNK_MS = 2_000L
        const val OVERLAP_MS = 400L
        const val STEP_MS = CHUNK_MS - OVERLAP_MS
        private const val RMS_THRESHOLD = 700.0
        fun chunkCount(durationMs: Long): Int = if (durationMs <= 0) 0 else max(1, ceil((durationMs - OVERLAP_MS).toDouble() / STEP_MS).toInt())
        fun startMs(index: Int): Long = index * STEP_MS
    }
}

data class AudioChunk(val file: File, val startElapsedMs: Long, val durationMs: Long, val sequence: Long = 0L)
