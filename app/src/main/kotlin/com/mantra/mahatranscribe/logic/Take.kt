package com.mantra.mahatranscribe.logic

/**
 * WHAT HAPPENS WHEN A TRANSCRIPT ARRIVES, AND THERE IS EXACTLY ONE OF THESE.
 *
 * recorder.md 1: two modes, because both answer the same question. SINGLE replaces the box,
 * MULTI appends, so a long piece can be done in sittings. Baba's words: *"I don't need to talk
 * 30 minutes. I can take a break, eat some kitchari, and then continue. How do you eat an
 * elephant? Spoon by spoon."*
 *
 * This is a better answer to a failed send than a retry button is. Retry rescues a send that
 * failed; MULTI means the recording was never half an hour long in the first place.
 *
 * ### One helper, every route
 *
 * The recorder, the opened file and pasted text ALL come through [deliver]. A mode that worked
 * for the recorder but not for an opened file would be worse than no mode at all, because it
 * would be right often enough to be trusted.
 *
 * Every decision below has a reason and each one is a test case:
 *
 *  - A BLANK LINE between takes, never a space. They are separate sittings and read as separate
 *    paragraphs; a space runs two thoughts together with no way to tell them apart afterwards.
 *  - EMPTY OR WHITESPACE-ONLY DELIVERY CHANGES NOTHING, in SINGLE mode too, so a failed
 *    transcription can never wipe work already in the box. This is the rule that makes the whole
 *    thing safe and it is the one a rewrite would drop.
 *  - THE EXISTING TEXT IS RIGHT-TRIMMED FIRST, so appending after a box that already ends in
 *    blank lines does not stack them up.
 *  - DEFAULT IS SINGLE, the older behaviour, so an upgrade changes nothing until asked.
 */
enum class Mode { SINGLE, MULTI }

object Take {

    /** The separator between two sittings. A blank line, which is two newlines. */
    const val JOIN = "\n\n"

    /**
     * The new [arriving] text laid against what is already in the box.
     *
     * @return the box's new contents. Never null, never shorter than [existing] in MULTI mode.
     */
    fun deliver(existing: String, arriving: String, mode: Mode): String {
        // Nothing usable arrived. Do not touch work that is already there. This branch is the
        // reason a failed send cannot cost anybody a paragraph.
        if (arriving.isBlank()) return existing
        val incoming = arriving.trim()
        if (mode == Mode.SINGLE) return incoming
        val kept = existing.trimEnd()
        if (kept.isEmpty()) return incoming
        return kept + JOIN + incoming
    }
}
