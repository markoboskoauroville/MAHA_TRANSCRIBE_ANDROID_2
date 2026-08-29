package com.mantra.mahatranscribe.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV, 16 kHz MONO PCM16, AND WHY THAT EXACT SHAPE.
 *
 * Lifted from TTT mini's recorder, where the reasoning was already paid for: WAV is accepted by
 * every transcription path this app has, and 16 kHz mono is exactly what speech models consume,
 * so there is no resampling on the way in and no quality lost on the way out. A phone's 48 kHz
 * stereo capture is three times the bytes for a transcript that comes back identical.
 *
 * ### The header is written twice, and that is the point
 *
 * A WAV header carries the length of the audio, which is not known until recording stops. The
 * naive approach holds the samples in memory and writes the whole file at the end. That is a bet
 * that nothing goes wrong for the length of the recording, and recorder.md 2 says the bet is
 * eventually lost: by a phone call, a low-memory kill, or a tab closed by mistake.
 *
 * So the header is written FIRST with zero lengths, samples are appended as they arrive, and the
 * two length fields are patched at the end. A file that never gets its patch is still a file
 * full of audio, and [repair] puts a correct header on it. **A crash costs one second, not the
 * take.**
 */
object Wav {

    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16
    const val HEADER_BYTES = 44

    /** Create [file] and write a 44-byte header claiming zero samples. */
    fun open(file: File): RandomAccessFile {
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.write(header(0))
        return raf
    }

    /** Patch the two length fields to match what is actually in [file]. Safe to call twice. */
    fun close(raf: RandomAccessFile) {
        val dataBytes = (raf.length() - HEADER_BYTES).coerceAtLeast(0)
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.fd.sync()
        raf.close()
    }

    /**
     * Put a correct header on a file whose recording was interrupted.
     *
     * This is the function that turns a killed process into a lost second instead of a lost take.
     * It is called on start-up for any recording found still open from last time.
     */
    fun repair(file: File): Boolean {
        if (!file.exists() || file.length() <= HEADER_BYTES) return false
        RandomAccessFile(file, "rw").use { raf ->
            val dataBytes = raf.length() - HEADER_BYTES
            raf.seek(0)
            raf.write(header(dataBytes))
        }
        return true
    }

    private fun header(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)                                  // PCM, uncompressed
        b.putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE)
        b.putInt(byteRate)
        b.putShort((CHANNELS * BITS / 8).toShort())    // block align
        b.putShort(BITS.toShort())
        b.put("data".toByteArray())
        b.putInt(dataBytes.toInt())
        return b.array()
    }

    /** Seconds of audio in a file of this size. Used for the timer and for the archive row. */
    fun seconds(file: File): Double {
        if (!file.exists()) return 0.0
        val data = (file.length() - HEADER_BYTES).coerceAtLeast(0)
        return data.toDouble() / (SAMPLE_RATE * CHANNELS * BITS / 8)
    }

    /** Write mono float samples in -1..1 as a complete PCM16 WAV. */
    fun write(file: File, samples: FloatArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(samples.size.toLong() * 2))
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
    }
}
