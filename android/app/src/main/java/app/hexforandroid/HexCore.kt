package app.hexforandroid

import org.json.JSONObject

/** Hex's Rust core (android/core): the transcribe.cpp engine and Hex's text steps. */
object HexCore {
    /** The libhexcore build this phone's CPU can run (see build-core.sh). */
    val variant: String = run {
        val features = runCatching {
            java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty().split(' ', '\t', ':')
        }.getOrDefault(emptyList())
        when {
            "asimddp" in features && "asimdhp" in features -> "dotprod"
            else -> "base"
        }
    }

    init {
        System.loadLibrary("hexcore_$variant")
    }

    @JvmStatic private external fun nativeLoad(path: String, threads: Int): Long
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeDescribe(handle: Long): String
    @JvmStatic private external fun nativeTranscribe(handle: Long, pcm: FloatArray, settingsJson: String): String
    @JvmStatic private external fun nativeProcess(transcript: String, stepsJson: String): String

    data class Run(val text: String, val audioMs: Long, val inferenceMs: Long)

    /** A loaded model. Calls are serialized by the caller (one worker thread). */
    class Engine internal constructor(private var handle: Long) {
        fun describe(): String = nativeDescribe(handle) + " cpu=$variant"

        /** [pcm] is 16 kHz mono in [-1, 1]. */
        fun transcribe(pcm: FloatArray, settings: JSONObject): Run {
            val json = JSONObject(nativeTranscribe(handle, pcm, settings.toString()))
            return Run(json.getString("text"), json.getLong("audio_ms"), json.getLong("inference_ms"))
        }

        fun close() {
            if (handle != 0L) nativeFree(handle)
            handle = 0L
        }
    }

    fun load(path: String, threads: Int): Engine = Engine(nativeLoad(path, threads))

    /** Spoken formatting, spoken symbols, filler words and replacements, as Hex orders them. */
    fun process(transcript: String, steps: JSONObject): String = nativeProcess(transcript, steps.toString())
}
