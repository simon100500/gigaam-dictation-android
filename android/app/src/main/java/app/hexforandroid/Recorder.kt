package app.hexforandroid

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * 16 kHz mono capture from the voice-recognition source (tuned for speech
 * recognition, without call-style processing). Samples go into one growing
 * buffer; each 20 ms read also reports an RMS level for the bubble. Float
 * capture where the phone offers it, 16-bit otherwise.
 */
class Recorder(private val onLevel: (Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var samples = FloatArray(SAMPLE_RATE * 30)
    private var count = 0
    private val lock = Any()

    /** True when Android hands this app silence because it may not record now. */
    @Volatile var silenced = false
        private set

    /** Why capture stopped early, if it did. */
    @Volatile var failure: String? = null
        private set

    @SuppressLint("MissingPermission") // Checked by the caller.
    fun start() {
        check(record == null) { "already recording" }
        val audio = open(AudioFormat.ENCODING_PCM_FLOAT) ?: open(AudioFormat.ENCODING_PCM_16BIT)
            ?: throw IllegalStateException("The microphone didn't open")
        audio.startRecording()
        record = audio
        running = true
        thread = Thread({ loop(audio) }, "hex-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(encoding: Int): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (minimum <= 0) return null
        val audio = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding, maxOf(minimum, SAMPLE_RATE))
        }.getOrNull() ?: return null
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return null
        }
        return audio
    }

    private fun loop(audio: AudioRecord) {
        val frame = SAMPLE_RATE / 50 // 20 ms
        val floats = FloatArray(frame)
        val shorts = if (audio.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(frame) else null
        var reads = 0
        while (running) {
            val n = if (shorts != null) {
                audio.read(shorts, 0, frame, AudioRecord.READ_BLOCKING).also { got ->
                    for (i in 0 until got) floats[i] = shorts[i] / 32768f
                }
            } else {
                audio.read(floats, 0, frame, AudioRecord.READ_BLOCKING)
            }
            if (n < 0) {
                // The audio server died or the record became invalid: stop, don't spin.
                failure = "The microphone stopped working"
                break
            }
            if (n == 0) continue
            append(floats, n)
            var sum = 0f
            for (i in 0 until n) sum += floats[i] * floats[i]
            onLevel(sqrt(sum / n))
            // Android may silence an app's capture at any time (privacy toggle, background rules).
            if (reads++ % 50 == 0) {
                silenced = silenced || audio.activeRecordingConfiguration?.isClientSilenced == true
            }
        }
    }

    private fun append(chunk: FloatArray, n: Int) = synchronized(lock) {
        if (count + n > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, count + n))
        System.arraycopy(chunk, 0, samples, count, n)
        count += n
    }

    /** Stops and returns everything captured. */
    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        thread = null
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
        return synchronized(lock) { samples.copyOf(count) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
