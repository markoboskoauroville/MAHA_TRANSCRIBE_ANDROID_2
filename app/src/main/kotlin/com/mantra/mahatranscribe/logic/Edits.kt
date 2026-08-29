package com.mantra.mahatranscribe.logic

/**
 * UNDO AND REDO FOR ONE TEXT BOX.
 *
 * ### Why it is visible when it can do nothing
 *
 * From the brief, and it is the whole reason this class exposes [canUndo] rather than letting
 * the screen hide the key: *"UNDO IS VISIBLE WHENEVER THERE IS TEXT, greyed when there is
 * nothing behind it. Somebody looks for undo BEFORE they need it, to know it is there. A safety
 * net you cannot see is not a safety net."*
 *
 * So the key is always drawn. This class answers whether pressing it would do anything, and the
 * screen turns that answer into ink rather than into presence. design-language.md 1: nothing
 * appears, nothing disappears, things become active or inactive.
 *
 * ### The trap this shape avoids
 *
 * MAHA_TRANSCRIBE_STREAMLIT carried a test called *"no undo when nothing was lost"* which was
 * green for thirty-four versions and was testing the exact behaviour Baba was complaining about
 * (checking-the-checks.md 8). It was perfectly correct about the wrong rule. The rule here is
 * stated once, in plain words, at the top: **every change to the box is undoable, including one
 * that made the box longer.** Undo is not a rescue for deletions, it is a way back.
 *
 * ### Shape
 *
 * A past, a present and a future. [push] records a new present and discards the future, which is
 * what every editor does and what people expect: typing after undoing abandons the redo branch.
 * The past is capped at [LIMIT] and the OLDEST is dropped, never the newest.
 */
class Edits(initial: String = "", private val limit: Int = LIMIT) {

    companion object {
        /** Twenty steps back, as in the Streamlit app. Enough for a sitting, small enough to hold. */
        const val LIMIT = 20
    }

    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    var present: String = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val depth: Int get() = past.size

    /**
     * Record [next] as the new contents.
     *
     * An identical value is NOT recorded. Every keystroke that changes nothing would otherwise
     * eat a step of history, and twenty steps of "no change" is a history that cannot reach
     * anything the person remembers doing.
     */
    fun push(next: String) {
        if (next == present) return
        past.addLast(present)
        // Drop from the FRONT when full. Dropping the newest would silently make undo skip a
        // step, which reads as the box jumping rather than as a limit being reached.
        while (past.size > limit) past.removeFirst()
        future.clear()
        present = next
    }

    /** Step back one. Returns the new contents, or the unchanged present if there is no past. */
    fun undo(): String {
        val prev = past.removeLastOrNull() ?: return present
        future.addLast(present)
        present = prev
        return present
    }

    /** Step forward one. Returns the new contents, or the unchanged present if there is no future. */
    fun redo(): String {
        val next = future.removeLastOrNull() ?: return present
        past.addLast(present)
        present = next
        return present
    }

    /**
     * Replace the contents without recording a step, for restoring a saved session.
     *
     * Coming back from a phone call must not look like an edit. If it did, the first undo after
     * reopening the app would take somebody to whatever was on screen before the restore, which
     * is nothing they ever typed.
     */
    fun reset(text: String) {
        past.clear(); future.clear(); present = text
    }
}
