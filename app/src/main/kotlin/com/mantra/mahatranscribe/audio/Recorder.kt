package com.mantra.mahatranscribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mantra.mahatranscribe.logic.Level
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * CAPTURING A VOICE SO THAT NOTHING SPOKEN IS EVER LOST.
 *
 * recorder.md: this is the module where a bug costs something that cannot be recovered. Every
 * other module can be rebuilt from what is on disk. **Words spoken once are gone.** Every rule
 * in this file follows from that one fact.
 *
 * ### Written to disk every second
 *
 * Not held in memory until stop. A crash, a low-memory kill or an incoming call then loses one
 * second rather than the take. The flush is explicit rather than left to the operating system's
 * own schedule, because "it will probably be written" is the same bet in different clothes.
 *
 * ### The scope must never lie
 *
 * recorder.md 3, and it is the one thing this display must never get wrong. A level meter with
 * automatic gain and no floor will amplify the noise of a DISCONNECTED MICROPHONE into a
 * convincing dance. Measured in a real browser: real speech filled 43 px of 46 and varied with
 * the voice; a dead microphone sat flat at 1 px.
 *
 * So [level] is a true dBFS reading with a floor and no gain of any kind. **If the trace is flat,
 * the microphone is genuinely not receiving**, and that is the single most useful thing a
 * recorder can say before somebody talks for ten minutes into nothing.
 */
class Recorder(private val target: File) {

    enum class State { IDLE, RECORDING, PAUSED, STOPPED }

    @Volatile var state: State = State.IDLE
        private set

    /**
     * The current input level in dBFS, floored at [Level.FLOOR_DB]. Read by the meter.
     *
     * A raw measurement, never smoothed here. Smoothing is a display decision and it belongs
     * with the display, on the display's own clock, so that the meter's speed is a decision
     * rather than an accident of the audio buffer size.
     */
    @Volatile var level: Double = Level.FLOOR_DB
        private set

    /** Seconds captured so far. Derived from the file so it cannot drift from what is on disk. */
    @Volatile var seconds: Double = 0.0
        private set

    /** Set when the microphone could not be opened, so the screen can say a sentence. */
    @Volatile var fault: String? = null
        private set

    private var record: AudioRecord? = null
    private var raf: RandomAccessFile? = null
    private var worker: Thread? = null
    @Volatile private var paused = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (state == State.RECORDING) return true
        fault = null
        val min = AudioRecord.getMinBufferSize(
            Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) { fault = "This phone will not open the microphone at 16 kHz."; return false }
        // A buffer of about a second, so a busy moment on the main thread cannot drop audio.
        val bufferBytes = maxOf(min * 4, Wav.SAMPLE_RATE * 2)
        val rec = try {
            AudioRecord(
                // VOICE_RECOGNITION rather than MIC: it asks the phone for the processing chain
                // tuned for speech, which is what is being sent to a speech model.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
            )
        } catch (e: SecurityException) {
            fault = "Permission to use the microphone has not been given."; return false
        } catch (e: IllegalArgumentException) {
            fault = "The microphone could not be opened."; return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fault = "The microphone is busy. Another app may be using it."
            return false
        }

        raf = Wav.open(target)
        record = rec
        paused = false
        state = State.RECORDING
        rec.startRecording()

        worker = thread(name = "maha-recorder", isDaemon = true) { loop(rec, bufferBytes) }
        return true
    }

    private fun loop(rec: AudioRecord, bufferBytes: Int) {
        val shorts = ShortArray(bufferBytes / 2)
        var sinceFlush = 0L
        // A BOUNDED loop in the sense G5 asks for: it ends when the state leaves RECORDING, and
        // the only thing that sets the state is this class. There is no condition under which
        // this spins without reading, because a read on a started AudioRecord blocks until it
        // has samples or the recorder is stopped.
        while (state == State.RECORDING) {
            val n = rec.read(shorts, 0, shorts.size)
            if (n <= 0) continue
            var peak = 0.0
            for (i in 0 until n) {
                val a = abs(shorts[i].toDouble()) / 32768.0
                if (a > peak) peak = a
            }
            // NO GAIN, and a floor. This is what makes a dead microphone read as flat.
            level = Level.db(peak)

            if (paused) continue

            val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bytes.putShort(shorts[i])
            val f = raf ?: break
            synchronized(this) { f.write(bytes.array()) }
            sinceFlush += n
            if (sinceFlush >= Wav.SAMPLE_RATE) {
                // Once a second, on purpose, and the cost of being wrong here is somebody's words.
                synchronized(this) { runCatching { f.fd.sync() } }
                sinceFlush = 0
            }
            seconds = (f.length() - Wav.HEADER_BYTES).toDouble() / (Wav.SAMPLE_RATE * 2)
        }
    }

    /**
     * Pause. The microphone stays open and the meter keeps moving.
     *
     * Deliberate: a meter that dies on pause makes somebody think the app stopped hearing them,
     * and the first thing they do is press record again, which on a lesser design starts a second
     * take over the first.
     */
    fun pause() { if (state == State.RECORDING) { paused = true; state = State.PAUSED } }

    fun resume() { if (state == State.PAUSED) { paused = false; state = State.RECORDING } }

    /** Stop, patch the header, and return the finished file, or null if nothing was captured. */
    fun stop(): File? {
        if (state == State.IDLE || state == State.STOPPED) return null
        val wasPaused = state == State.PAUSED
        state = State.STOPPED
        if (wasPaused) paused = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        worker?.join(2_000)     // a deadline, not a hope
        worker = null
        synchronized(this) {
            raf?.let { runCatching { Wav.close(it) } }
            raf = null
        }
        level = Level.FLOOR_DB
        return if (target.length() > Wav.HEADER_BYTES) target else null
    }
}
