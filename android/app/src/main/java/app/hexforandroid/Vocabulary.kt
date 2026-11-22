package app.hexforandroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One vocabulary term and the other spellings a transcript may show for it, as desktop Hex stores them. */
data class Term(val term: String, val soundsLike: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("term", term).put("sounds_like", JSONArray(soundsLike))

    companion object {
        fun fromJson(o: JSONObject) = Term(
            o.optString("term").trim(),
            o.optJSONArray("sounds_like")?.let { a -> List(a.length()) { a.getString(it).trim() } }.orEmpty().filter { it.isNotEmpty() },
        )
    }
}

/**
 * The vocabulary list and its sync with desktop Hex.
 *
 * Sync goes to `tools/hex-vocab-sync` on the laptop over Tailscale. The phone
 * sends its list and the list both sides agreed on at the last sync (the
 * base). The laptop merges three ways against desktop Hex's list, so an
 * addition or deletion on either side survives, and returns the result. The
 * phone keeps the result as both its list and its new base.
 */
object Vocabulary {
    private const val TAG = "HexVocabulary"
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hex", Context.MODE_PRIVATE)

    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    fun entries(context: Context): List<Term> {
        val prefs = prefs(context)
        val stored = prefs.getString("vocabulary_entries", null)
        if (stored == null) {
            // First run after the old "Term | sounds like" text box: carry it over.
            val legacy = prefs.getString("vocabulary", "").orEmpty().lines().mapNotNull { line ->
                val parts = line.split('|', limit = 2)
                val term = parts[0].trim()
                if (term.isEmpty()) null
                else Term(term, parts.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty())
            }
            save(context, legacy, notify = false)
            return legacy
        }
        return parse(stored)
    }

    fun save(context: Context, list: List<Term>, notify: Boolean = true) {
        val clean = list.filter { it.term.isNotBlank() }
        prefs(context).edit().putString("vocabulary_entries", JSONArray(clean.map { it.toJson() }).toString()).apply()
        if (notify) changed()
    }

    private fun base(context: Context) = parse(prefs(context).getString("vocabulary_base", "[]")!!)

    private fun parse(json: String): List<Term> = JSONArray(json).let { a -> List(a.length()) { Term.fromJson(a.getJSONObject(it)) } }

    // --- Sync ---------------------------------------------------------------

    fun paired(context: Context) = prefs(context).getString("sync_url", null) != null

    /** Forgets the desktop; the vocabulary itself stays. */
    fun unpair(context: Context) {
        prefs(context).edit()
            .remove("sync_url").remove("sync_token").remove("sync_desktop")
            .remove("sync_error").remove("sync_at").remove("sync_tried").remove("vocabulary_base")
            .apply()
        changed()
    }

    fun desktopName(context: Context) = prefs(context).getString("sync_desktop", null) ?: "your desktop"

    /** "Synced 2 min ago", or why the last attempt failed. */
    fun status(context: Context): String {
        val prefs = prefs(context)
        if (!paired(context)) return "Not paired with desktop Hex"
        prefs.getString("sync_error", null)?.let { return it }
        val at = prefs.getLong("sync_at", 0L)
        if (at == 0L) return "Not synced yet"
        return "Synced with ${desktopName(context)} ${History.ago(at)}"
    }

    /**
     * Takes the pairing file `tools/hex-vocab-sync pair` pushes over USB
     * (address and token), keeps it in private storage, and deletes the file.
     */
    fun importPairing(context: Context): Boolean {
        val file = File(context.getExternalFilesDir(null), "pair.json")
        if (!file.isFile) return false
        return try {
            val pairing = JSONObject(file.readText())
            prefs(context).edit()
                .putString("sync_url", pairing.getString("url").trimEnd('/'))
                .putString("sync_token", pairing.getString("token"))
                .putString("sync_desktop", pairing.optString("desktop", "your desktop"))
                .remove("sync_error")
                .apply()
            file.delete()
            true
        } catch (e: Exception) {
            Log.w(TAG, "pairing file unreadable", e)
            false
        }
    }

    /** Syncs in the background unless a sync is running or ran within [minGapMs]. */
    fun sync(context: Context, minGapMs: Long = 0) {
        val app = context.applicationContext
        importPairing(app)
        if (!paired(app)) return
        val prefs = prefs(app)
        if (System.currentTimeMillis() - prefs.getLong("sync_tried", 0L) < minGapMs) return
        if (!running.compareAndSet(false, true)) return
        prefs.edit().putLong("sync_tried", System.currentTimeMillis()).apply()
        thread(name = "hex-vocab-sync") {
            try {
                val url = URL(prefs.getString("sync_url", null) + "/v1/vocabulary/sync")
                val sent = entries(app)
                val body = JSONObject()
                    .put("entries", JSONArray(sent.map { it.toJson() }))
                    .put("base", JSONArray(base(app).map { it.toJson() }))
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    // Tailscale may need a few seconds to wake its tunnel.
                    connectTimeout = 10_000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + prefs.getString("sync_token", ""))
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
                if (code == 401) error("Pair again: run hex-vocab-sync pair on the desktop")
                if (code !in 200..299) {
                    val reason = runCatching { JSONObject(text).getString("error") }.getOrNull()
                    error(reason ?: "Desktop Hex answered $code")
                }
                val reply = JSONObject(text)
                val merged = parse(reply.getJSONArray("entries").toString())
                val raw = JSONArray(merged.map { it.toJson() }).toString()
                // Edited while the sync ran: keep the edit; the merge result becomes
                // the base, so the next sync carries the edit over.
                val editedMeanwhile = entries(app) != sent
                prefs.edit()
                    .apply { if (!editedMeanwhile) putString("vocabulary_entries", raw) }
                    .putString("vocabulary_base", raw)
                    .putLong("sync_at", System.currentTimeMillis())
                    .putString("sync_desktop", reply.optString("desktop", desktopName(app)))
                    .remove("sync_error")
                    .apply()
                Log.i(TAG, "synced ${merged.size} terms")
                if (editedMeanwhile) main.post { sync(app) }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "sync failed: ${e.message}")
                fail(prefs, when (e) {
                    is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.UnknownHostException ->
                        "Can't reach ${desktopName(app)}. Is Tailscale on?"
                    is java.net.SocketTimeoutException ->
                        if (e.message.orEmpty().contains("connect", ignoreCase = true)) "Can't reach ${desktopName(app)}. Is Tailscale on?"
                        else "${desktopName(app)} didn't answer in time"
                    else -> e.message ?: "Sync failed"
                })
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
                fail(prefs, e.message ?: "Sync failed")
            } finally {
                running.set(false)
                changed()
            }
        }
    }

    private fun fail(prefs: android.content.SharedPreferences, message: String) {
        prefs.edit().putString("sync_error", message).apply()
    }
}

/** Recent dictations with the time they finished. */
object History {
    data class Item(val text: String, val at: Long)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hex", Context.MODE_PRIVATE)

    fun items(context: Context): List<Item> {
        val prefs = prefs(context)
        val stored = prefs.getString("history_items", null)
        if (stored == null) {
            // The first version kept bare strings.
            val legacy = JSONArray(prefs.getString("history", "[]")).let { a -> List(a.length()) { Item(a.getString(it), 0L) } }
            write(context, legacy)
            return legacy
        }
        return JSONArray(stored).let { a ->
            List(a.length()) { a.getJSONObject(it).let { o -> Item(o.getString("text"), o.optLong("at")) } }
        }
    }

    fun add(context: Context, text: String) = write(context, listOf(Item(text, System.currentTimeMillis())) + items(context))

    fun clear(context: Context) = write(context, emptyList())

    private fun write(context: Context, items: List<Item>) {
        val array = JSONArray(items.take(LIMIT).map { JSONObject().put("text", it.text).put("at", it.at) })
        prefs(context).edit().putString("history_items", array.toString()).apply()
    }

    fun ago(at: Long): String {
        if (at == 0L) return ""
        val seconds = (System.currentTimeMillis() - at) / 1000
        return when {
            seconds < 45 -> "just now"
            seconds < 3600 -> "${(seconds + 30) / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    private const val LIMIT = 50
}
