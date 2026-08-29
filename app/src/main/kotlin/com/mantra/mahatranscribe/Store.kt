package com.mantra.mahatranscribe

import android.content.Context
import com.mantra.mahatranscribe.logic.Keys
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * THE WORK MUST SURVIVE A PHONE CALL.
 *
 * From the brief, and it is the requirement that decides this whole file:
 *
 * > *"Somebody switches to WhatsApp mid-sentence and comes back. Everything they had typed, the
 * > tab they were on, and every choice they made must still be there. This is the single most
 * > common way to lose somebody's work and it is not a crash, so nothing reports it."*
 *
 * On Android this is not one problem, it is four, and only the first is obvious:
 *
 *  1. **Process death.** The system kills a backgrounded app to free memory and gives no warning.
 *     Anything in a variable is gone.
 *  2. **Configuration change.** A rotation or a font-size change destroys and rebuilds the screen.
 *  3. **The call takes the microphone.** An incoming call is not just a background event, it takes
 *     the input device away.
 *  4. **Coming back looks like starting.** The rebuilt screen has to restore rather than reset,
 *     and a restore must not register as an edit — see [logic.Edits.reset].
 *
 * ### Written on change, never on exit
 *
 * There is no `onPause` save here and there must never be one. A save on exit is a save that runs
 * when the system is polite enough to tell you, and process death is not polite. Every setter
 * below writes immediately. The file is small, the writes are rare on the scale a disk cares
 * about, and the cost of being wrong is somebody's paragraph.
 *
 * ### Two files, on purpose
 *
 * The keys are separate from the session. keyring.md 7 wants the ring in its own small file
 * because it is read before every job, and the session file grows with the text somebody is
 * writing. Putting them together would mean parsing a page of prose to answer "which key".
 *
 * **Neither file is ever in the repository.** secrets.md 3.
 */
class Store(context: Context) {

    private val dir: File = context.filesDir
    private val sessionFile = File(dir, "session.json")
    private val keysFile = File(dir, "keys.json")

    // ---- THE SESSION -----------------------------------------------------------------------

    private var session: JSONObject = read(sessionFile)

    /** The contents of the box. The single most important thing in this file. */
    var text: String
        get() = session.optString("text", "")
        set(v) { session.put("text", v); write(sessionFile, session) }

    /** Which of the six screens was open. */
    var tab: String
        get() = session.optString("tab", "T")
        set(v) { session.put("tab", v); write(sessionFile, session) }

    /** HR, EN or AUTO. */
    var spoken: String
        get() = session.optString("spoken", "HR")
        set(v) { session.put("spoken", v); write(sessionFile, session) }

    /** SINGLE or MULTI. Default SINGLE, the older behaviour, so an upgrade changes nothing. */
    var mode: String
        get() = session.optString("mode", "SINGLE")
        set(v) { session.put("mode", v); write(sessionFile, session) }

    /** Which provider holds each role, by role name. Read through [logic.Roles.resolve]. */
    fun route(role: String): String? = session.optString("route_$role", "").ifEmpty { null }
    fun setRoute(role: String, provider: String) {
        session.put("route_$role", provider); write(sessionFile, session)
    }

    /** Text scale and interface scale, each 1.0 by default, each with its own control. */
    var textScale: Float
        get() = session.optDouble("textScale", 1.0).toFloat()
        set(v) { session.put("textScale", v.toDouble()); write(sessionFile, session) }

    var uiScale: Float
        get() = session.optDouble("uiScale", 1.0).toFloat()
        set(v) { session.put("uiScale", v.toDouble()); write(sessionFile, session) }

    // ---- THE KEYS --------------------------------------------------------------------------

    private var keys: JSONObject = read(keysFile)

    /** Every key held for [provider], in ring order. */
    fun keysFor(provider: Keys.Provider): List<String> {
        val arr = keys.optJSONArray(provider.name) ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Add keys, keeping what is already there.
     *
     * ADDS, NEVER REPLACES. The brief says this about importing tags and the same reasoning
     * applies to keys: somebody loading a file expects to gain what is in it, not to lose what
     * they had. De-duplicated on the way in, first appearance kept.
     */
    fun addKeys(provider: Keys.Provider, incoming: List<String>): Int {
        val have = keysFor(provider).toMutableList()
        var added = 0
        for (k in incoming) if (k !in have) { have.add(k); added++ }
        keys.put(provider.name, JSONArray(have)); write(keysFile, keys)
        return added
    }

    fun removeKey(provider: Keys.Provider, key: String) {
        keys.put(provider.name, JSONArray(keysFor(provider).filter { it != key })); write(keysFile, keys)
    }

    /** Hume pairs, stored together and never split across the ring. */
    fun humePairs(): List<Keys.HumePair> {
        val arr = keys.optJSONArray("HUME_PAIRS") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Keys.HumePair(o.optString("name"), o.getString("apiKey"), o.getString("secret"))
        }
    }

    fun addHumePairs(incoming: List<Keys.HumePair>): Int {
        val have = humePairs().toMutableList()
        var added = 0
        for (p in incoming) if (have.none { it.apiKey == p.apiKey }) { have.add(p); added++ }
        keys.put("HUME_PAIRS", JSONArray(have.map {
            JSONObject().put("name", it.name).put("apiKey", it.apiKey).put("secret", it.secret)
        }))
        write(keysFile, keys)
        return added
    }

    // ---- THE PLUMBING ----------------------------------------------------------------------

    /**
     * A corrupt or half-written file returns an empty object rather than throwing.
     *
     * Settings are a convenience and transcription is the product. A failure in the convenience
     * must never be able to stop the product, which is a rule the Streamlit app learned the hard
     * way: a crash in *settings persistence* killed transcribing and reading for everybody,
     * because the call sat at module scope with no handling around it.
     */
    private fun read(f: File): JSONObject =
        runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())

    /**
     * Write through a temporary file and rename.
     *
     * A process killed halfway through a write leaves a truncated file, and a truncated session
     * file is a lost paragraph. A rename on the same filesystem is atomic, so the file on disk is
     * always either the old one entire or the new one entire.
     */
    private fun write(f: File, o: JSONObject) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        }
    }
}
