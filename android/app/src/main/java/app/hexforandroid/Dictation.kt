package app.hexforandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.sin

/**
 * One dictation at a time: record, transcribe with Hex's engine, run Hex's
 * text steps, hand the text to the caller. The model loads once when
 * [DictationService] arms Hex and stays loaded until it disarms.
 *
 * Threads: [state] changes only on the main thread; the engine is loaded,
 * used and closed only on the single worker thread.
 */
object Dictation {
    enum class Phase { Off, Loading, Ready, Recording, Transcribing }

    data class State(
        val phase: Phase = Phase.Off,
        val level: Float = 0f,
        val message: String? = null,
        val engine: String? = null,
        val last: HexCore.Run? = null,
    ) {
        /** What a screen shows besides the live level: listeners redraw only when this changes. */
        val shape get() = listOf(phase, message, engine, last)
    }

    private const val TAG = "HexDictation"
    /** Shorter captures are taps, not speech. */
    private const val MIN_SAMPLES = Recorder.SAMPLE_RATE / 4
    /** A tapped recording left running stops on its own after this long. */
    private const val MAX_RECORDING_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "hex-engine") }
    private val listeners = mutableSetOf<(State) -> Unit>()
    private lateinit var app: Context
    @Volatile private var engine: HexCore.Engine? = null
    private var recorder: Recorder? = null
    private var focus: AudioFocusRequest? = null
    /** Bumped on every arm and disarm, so work from an earlier session can't touch the state. */
    private var session = 0
    private val timeLimit = Runnable { finish { Delivery.deliver(app, it) } }
    /** The start sound waits a moment, so a press that turns into a drag stays silent. */
    private val startSound = Runnable { Sounds.play(app, Sounds.Cue.Start) }
    private var stoppedAt = 0L

    @Volatile var state = State()
        private set

    val armed get() = state.phase != Phase.Off

    val recording get() = recorder != null

    @Volatile var previewing = false
        private set

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun listen(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun unlisten(listener: (State) -> Unit) {
        listeners -= listener
    }

    /**
     * Applies [change] on the main thread (at once when already there, so a
     * caller sees its own change), unless [onlyIn] names an earlier session.
     */
    private fun update(onlyIn: Int? = null, change: State.() -> State) {
        val apply = Runnable {
            if (onlyIn != null && onlyIn != session) return@Runnable
            state = state.change()
            listeners.toList().forEach { it(state) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    /** Loads the model on the worker thread. Called on the main thread by [DictationService]. */
    fun arm(context: Context) {
        init(context)
        if (armed) return
        val mine = ++session
        update { copy(phase = Phase.Loading, message = null) }
        val threads = Settings(app).threadCount()
        worker.execute {
            try {
                if (!ModelStore.isPresent(app)) error("Download the speech model first")
                if (!ModelStore.isVerified(app) && !ModelStore.verify(app)) error("The model file is damaged. Download it again")
                engine?.close()
                val loaded = HexCore.load(ModelStore.file(app).absolutePath, threads)
                engine = loaded
                val description = loaded.describe()
                Log.i(TAG, "engine ready: $description")
                update(mine) { copy(phase = if (phase == Phase.Loading) Phase.Ready else phase, engine = description) }
            } catch (e: Throwable) {
                Log.e(TAG, "load failed", e)
                main.post {
                    if (mine != session) return@post
                    cancel()
                    update(mine) { copy(phase = Phase.Off, message = e.message ?: e.toString()) }
                }
            }
        }
    }

    /** Called on the main thread when Hex turns off. */
    fun disarm() {
        cancel()
        session++
        worker.execute {
            engine?.close()
            engine = null
        }
        update { State() }
    }

    fun micAllowed() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording; speech captured while the model loads waits for it. Returns false if Hex can't record now. */
    fun start(): Boolean {
        if (!armed || recorder != null || previewing || state.phase == Phase.Transcribing) return false
        if (!micAllowed()) {
            update { copy(message = "Hex needs the microphone permission") }
            return false
        }
        val capture = Recorder { level -> update { copy(level = level) } }
        try {
            capture.start()
        } catch (e: Throwable) {
            update { copy(message = e.message) }
            return false
        }
        recorder = capture
        duck(true)
        main.postDelayed(timeLimit, MAX_RECORDING_MS)
        main.postDelayed(startSound, 150)
        update { copy(phase = Phase.Recording, level = 0f, message = null) }
        return true
    }

    private fun idlePhase() = if (engine != null) Phase.Ready else Phase.Loading

    /** Discards the current recording. */
    fun cancel() {
        val capture = recorder ?: return
        recorder = null
        main.removeCallbacks(timeLimit)
        // Played the start sound already: this was a real recording, not the start of a drag.
        if (main.hasCallbacks(startSound)) main.removeCallbacks(startSound) else Sounds.play(app, Sounds.Cue.Cancel)
        capture.stop()
        duck(false)
        update { copy(phase = if (phase == Phase.Off) Phase.Off else idlePhase(), level = 0f) }
    }

    /** Stops recording, transcribes, and calls [deliver] on the main thread with the final text. */
    fun finish(deliver: (String) -> Unit) {
        val capture = recorder ?: return
        recorder = null
        main.removeCallbacks(timeLimit)
        main.removeCallbacks(startSound)
        val pcm = capture.stop()
        duck(false)
        when {
            capture.failure != null -> {
                Sounds.play(app, Sounds.Cue.Error)
                update { copy(phase = idlePhase(), level = 0f, message = capture.failure) }
                return
            }
            capture.silenced -> {
                Sounds.play(app, Sounds.Cue.Error)
                update { copy(phase = idlePhase(), level = 0f, message = "Android blocked the microphone. Open Hex and turn it on again") }
                return
            }
            pcm.size < MIN_SAMPLES -> {
                update { copy(phase = idlePhase(), level = 0f) }
                return
            }
        }
        val mine = session
        Sounds.play(app, Sounds.Cue.Stop)
        stoppedAt = SystemClock.uptimeMillis()
        update { copy(phase = Phase.Transcribing, level = 0f) }
        worker.execute {
            try {
                val settings = Settings(app)
                val loaded = engine ?: error("The speech model isn't loaded")
                val run = loaded.transcribe(pcm, settings.runSettings())
                val text = HexCore.process(run.text, settings.textSteps()).trim()
                Log.i(TAG, "audio ${run.audioMs} ms, inference ${run.inferenceMs} ms")
                main.post {
                    // Hex turned off meanwhile: drop the result rather than type it somewhere.
                    if (mine != session) return@post
                    if (text.isNotEmpty()) {
                        History.add(app, text)
                        deliver(text)
                        // As on the desktop: a done sound only when the text lands well after the stop sound.
                        if (SystemClock.uptimeMillis() - stoppedAt >= 2000) Sounds.play(app, Sounds.Cue.Done)
                    }
                    update(mine) { copy(phase = Phase.Ready, last = run, message = if (text.isEmpty()) "Nothing heard" else null) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "transcription failed", e)
                main.post { if (mine == session) Sounds.play(app, Sounds.Cue.Error) }
                update(mine) { copy(phase = idlePhase(), message = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Plays the bubble through a dictation without recording: three seconds
     * of a speech-like level, a second and a half of transcribing, then back.
     * The settings page's Preview, like desktop Hex's HUD Lab.
     */
    fun preview() {
        if (recording || previewing || state.phase == Phase.Transcribing) return
        previewing = true
        val before = state.phase
        val started = SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val t = (SystemClock.uptimeMillis() - started) / 1000.0
                when {
                    t < 3.2 -> {
                        // Syllables at about 5 Hz inside words of about a second.
                        val syllable = 0.5 + 0.5 * sin(2 * Math.PI * 5.1 * t)
                        val word = (sin(2 * Math.PI * 0.9 * t) + 0.35).coerceAtLeast(0.0)
                        val level = (0.002 + 0.09 * syllable * word * (0.7 + 0.3 * sin(13.0 * t))).toFloat()
                        update { copy(phase = Phase.Recording, level = level) }
                        main.postDelayed(this, 16)
                    }
                    t < 4.8 -> {
                        update { copy(phase = Phase.Transcribing, level = 0f) }
                        main.postDelayed(this, 50)
                    }
                    else -> {
                        update { copy(phase = before, level = 0f) }
                        previewing = false
                    }
                }
            }
        }
        main.post(tick)
    }

    /** Lowers other audio while recording, as Hex's desktop "duck" setting does. */
    private fun duck(on: Boolean) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        if (on && Settings(app).duckAudio) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
        }
    }
}
