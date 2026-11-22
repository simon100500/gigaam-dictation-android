package app.hexforandroid

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Desktop Hex's feedback sounds (`src/feedback.rs`, files from
 * `resources/audio/sounds/`): the same seven sets, the same cues, played at
 * desktop Hex's default volume. Off unless the user turns them on.
 */
object Sounds {
    enum class Set(val id: String, val label: String, val character: String) {
        Signature("signature", "Hex signature", "A kalimba motif that rises and comes home"),
        Classic("classic", "Classic", "The tones Hex first shipped with"),
        Click("click", "Click", "Mechanical key clicks"),
        Soft("soft", "Soft tone", "Warm, rounded blips"),
        Wood("wood", "Wood", "Soft marimba and hollow wood blocks"),
        Analog("analog", "Warm synth", "A mellow analogue synth"),
        Thock("thock", "Thock", "A deep, creamy keyboard key");

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id } ?: Classic
        }
    }

    enum class Cue(val id: String) { Start("start"), Stop("stop"), Cancel("cancel"), Error("error"), Done("done") }

    /** Desktop Hex's default sound volume. */
    private const val VOLUME = 0.5f

    private var pool: SoundPool? = null
    private val loaded = mutableMapOf<Int, Int>()
    private val ready = mutableSetOf<Int>()
    private val pending = mutableSetOf<Int>()

    /** Plays [cue] of the chosen set if sounds are on. Main thread. */
    fun play(context: Context, cue: Cue) {
        val settings = Settings(context)
        if (!settings.sounds) return
        play(context, Set.of(settings.soundSet), cue)
    }

    /** Plays [cue] of [set] regardless of the setting, as the picker auditions it. */
    fun play(context: Context, set: Set, cue: Cue) {
        val resource = resource(context, set, cue) ?: return
        val sounds = pool ?: SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .also { created ->
                pool = created
                created.setOnLoadCompleteListener { p, sample, status ->
                    if (status == 0) {
                        ready += sample
                        if (pending.remove(sample)) p.play(sample, VOLUME, VOLUME, 1, 0, 1f)
                    }
                }
            }
        val sample = loaded.getOrPut(resource) { sounds.load(context.applicationContext, resource, 1) }
        if (sample in ready) sounds.play(sample, VOLUME, VOLUME, 1, 0, 1f) else pending += sample
    }

    /** The set's file for [cue]. Classic has no error sound of its own; like desktop Hex off macOS, it borrows Signature's. */
    private fun resource(context: Context, set: Set, cue: Cue): Int? {
        fun find(s: Set) = context.resources.getIdentifier("sound_${s.id}_${cue.id}", "raw", context.packageName).takeIf { it != 0 }
        return find(set) ?: if (cue == Cue.Error) find(Set.Signature) else null
    }
}
