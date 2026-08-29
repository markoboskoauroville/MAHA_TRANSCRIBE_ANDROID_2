package com.mantra.mahatranscribe.logic

/**
 * ASKING FOR A JOB, NOT FOR A COMPANY.
 *
 * provider-router.md 1. The calling code asks for a ROLE — transcription, voice, translation —
 * and never for a company by name. The mapping is a setting. That is what lets a provider be
 * swapped, dropped or added without a line changing anywhere else, and providers do get dropped:
 * Edge was removed from this app on 29.8.2026 by one instruction, and nothing outside this file
 * and the settings store had to know.
 *
 * **Roles and providers are not the same shape.** One provider can hold two roles and one role
 * can move between providers. Groq holds two here already. A structure that assumes one to one
 * will be wrong within a month.
 *
 * ### What it cost when this was got wrong
 *
 * TTT mini's API screen used to ask, per provider, with a `transcription` and a `rewording` chip.
 * Groq arrived, advertised transcription because it genuinely can transcribe, and ended up
 * holding the transcription role. Every word Baba dictated went to Groq's Whisper instead of
 * AssemblyAI, silently, with the screen showing it as a correct configuration.
 */
enum class Role {
    /** Speech to text. Groq for fast, AssemblyAI for accurate. */
    TRANSCRIPTION,

    /** Text to speech. Speechify and Hume. Not used by the T tab; declared so R inherits it. */
    VOICE,

    /** Text in one language out in another. Groq. Not used by the T tab. */
    TRANSLATION,
}

/**
 * Which provider does which job.
 *
 * The defaults are Baba's instruction of 29.8.2026: transcription from Groq and AssemblyAI,
 * voices from Speechify and Hume, translation from Groq, and no Edge at all.
 */
object Roles {

    val DEFAULTS: Map<Role, Keys.Provider> = mapOf(
        // ACCURATE IS THE DEFAULT FOR DICTATION, not fast. A fast model returns fluent text that
        // is the wrong words, and there is nothing in the result to notice. Speed is the wrong
        // thing to optimise when the output is somebody's writing.
        Role.TRANSCRIPTION to Keys.Provider.ASSEMBLYAI,
        Role.VOICE to Keys.Provider.SPEECHIFY,
        Role.TRANSLATION to Keys.Provider.GROQ,
    )

    /** Every provider that can legitimately hold [role], for the settings screen to offer. */
    fun optionsFor(role: Role): List<Keys.Provider> = when (role) {
        Role.TRANSCRIPTION -> listOf(Keys.Provider.ASSEMBLYAI, Keys.Provider.GROQ)
        Role.VOICE -> listOf(Keys.Provider.SPEECHIFY, Keys.Provider.HUME)
        Role.TRANSLATION -> listOf(Keys.Provider.GROQ)
    }

    /**
     * The provider currently holding [role], given what was stored.
     *
     * A stored choice naming a provider that no longer exists, or one that can no longer hold
     * this role, falls back to the default rather than failing. provider-router.md test 4: a
     * stored provider choice from the previous version must still parse, INCLUDING one naming a
     * provider that has since been removed. That is not hypothetical here — anybody upgrading
     * from a build made before Edge was dropped has exactly that in their settings.
     */
    fun resolve(role: Role, stored: String?): Keys.Provider {
        val fallback = DEFAULTS.getValue(role)
        if (stored.isNullOrBlank()) return fallback
        val named = runCatching { Keys.Provider.valueOf(stored) }.getOrNull() ?: return fallback
        return if (named in optionsFor(role)) named else fallback
    }
}

/**
 * WHAT LANGUAGE THE PERSON IS ABOUT TO SPEAK.
 *
 * From the brief: *"Before recording: HR, ENG or AUTO. Auto works; naming the language is more
 * accurate."* That sentence is the whole design. AUTO exists and is honest about being second
 * best, because provider-router.md 5 measured automatic detection, found it unreliable enough to
 * matter, and removed it from another app. A wrong language does not fail visibly; it returns
 * confident nonsense in the other language.
 *
 * **This is the language of the SPEECH, not of the interface, and it is not the voice list.**
 * Croatian voices were dropped on 29.8.2026 because Speechify has no `hr-HR` at all across 985
 * voices. Croatian TRANSCRIPTION was not dropped and must not be: AssemblyAI and Groq both hear
 * it, and Baba is a Croatian speaker dictating in Croatian. Reading aloud and hearing are two
 * different roles and a decision about one is not a decision about the other.
 */
enum class Spoken(val code: String?, val label: String) {
    HR("hr", "HR"),
    EN("en", "ENG"),

    /** Let the transcriber decide. `null` means no language_code is sent at all. */
    AUTO(null, "AUTO");

    companion object {
        /** Croatian first, matching every other screen in this account. */
        val ORDER = listOf(HR, EN, AUTO)

        fun of(stored: String?): Spoken =
            ORDER.firstOrNull { it.name == stored } ?: HR
    }
}
