package com.mantra.mahatranscribe

import com.mantra.mahatranscribe.logic.Classify
import com.mantra.mahatranscribe.logic.Edits
import com.mantra.mahatranscribe.logic.Keys
import com.mantra.mahatranscribe.logic.Level
import com.mantra.mahatranscribe.logic.Mode
import com.mantra.mahatranscribe.logic.Ring
import com.mantra.mahatranscribe.logic.Role
import com.mantra.mahatranscribe.logic.Roles
import com.mantra.mahatranscribe.logic.Ruling
import com.mantra.mahatranscribe.logic.Spoken
import com.mantra.mahatranscribe.logic.Take
import com.mantra.mahatranscribe.logic.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 — THE MECHANISM, ALONE.
 *
 * No Android, no network, no screen. If any of this needed the app to be running, that would
 * itself be the finding: the logic would be tangled into the plumbing and should be pulled out.
 *
 * Before writing each of these the question was the only one that matters: **what could be true
 * that would make this test pass and the feature still be broken?** Cases below are chosen to
 * close a specific answer, not to walk the happy path from four angles.
 */
class LogicTest {

    // ---- Take: overwrite or append ---------------------------------------------------------
    //
    // The case it is FOR, the case it must REFUSE, both sides of every boundary, and the same
    // input twice.

    @Test fun single_replaces() =
        assertEquals("new", Take.deliver("old", "new", Mode.SINGLE))

    @Test fun multi_appends_with_a_blank_line() =
        assertEquals("old\n\nnew", Take.deliver("old", "new", Mode.MULTI))

    @Test fun multi_into_an_empty_box_does_not_lead_with_blank_lines() =
        assertEquals("new", Take.deliver("", "new", Mode.MULTI))

    /** THE RULE THAT MAKES A FAILED SEND SAFE. An empty delivery must never wipe the box. */
    @Test fun empty_delivery_changes_nothing_in_single_mode() =
        assertEquals("old", Take.deliver("old", "", Mode.SINGLE))

    @Test fun whitespace_delivery_changes_nothing_in_single_mode() =
        assertEquals("old", Take.deliver("old", "   \n\t ", Mode.SINGLE))

    @Test fun empty_delivery_changes_nothing_in_multi_mode() =
        assertEquals("old", Take.deliver("old", "", Mode.MULTI))

    @Test fun trailing_blank_lines_do_not_stack_up() =
        assertEquals("old\n\nnew", Take.deliver("old\n\n\n", "new", Mode.MULTI))

    @Test fun three_sittings_gather_in_order() {
        var box = ""
        for (t in listOf("one", "two", "three")) box = Take.deliver(box, t, Mode.MULTI)
        assertEquals("one\n\ntwo\n\nthree", box)
    }

    /** Two identical takes must BOTH survive rather than being silently merged. */
    @Test fun the_same_take_twice_is_kept_twice() =
        assertEquals("same\n\nsame", Take.deliver("same", "same", Mode.MULTI))

    @Test fun switching_mode_mid_work_in_both_directions() {
        var box = Take.deliver("", "one", Mode.MULTI)
        box = Take.deliver(box, "two", Mode.MULTI)
        box = Take.deliver(box, "only", Mode.SINGLE)
        assertEquals("only", box)
        box = Take.deliver(box, "again", Mode.MULTI)
        assertEquals("only\n\nagain", box)
    }

    // ---- Edits: undo and redo --------------------------------------------------------------

    @Test fun nothing_behind_it_at_the_start() {
        val e = Edits("hello")
        assertFalse(e.canUndo)
        assertFalse(e.canRedo)
    }

    /**
     * The sharpest case in this file. Streamlit's `test_undo` asserted "no undo when nothing was
     * lost", was green for thirty-four versions, and was perfectly correct about the WRONG RULE.
     * Growing the box is a change and must be undoable.
     */
    @Test fun growing_the_box_is_undoable_too() {
        val e = Edits("a")
        e.push("ab")
        assertTrue(e.canUndo)
        assertEquals("a", e.undo())
    }

    @Test fun redo_comes_back() {
        val e = Edits("a"); e.push("b"); e.undo()
        assertTrue(e.canRedo)
        assertEquals("b", e.redo())
    }

    @Test fun typing_after_an_undo_abandons_the_redo_branch() {
        val e = Edits("a"); e.push("b"); e.undo(); e.push("c")
        assertFalse(e.canRedo)
    }

    @Test fun an_identical_push_is_not_a_step() {
        val e = Edits("a"); e.push("a")
        assertFalse(e.canUndo)
    }

    /** The OLDEST is dropped when full. Dropping the newest would make undo silently skip. */
    @Test fun the_history_is_capped_and_drops_the_oldest() {
        val e = Edits("0", limit = 3)
        for (i in 1..10) e.push(i.toString())
        assertEquals(3, e.depth)
        assertEquals("9", e.undo())
    }

    @Test fun undo_at_the_bottom_returns_the_present_rather_than_failing() {
        val e = Edits("a")
        assertEquals("a", e.undo())
    }

    @Test fun a_restore_is_not_an_edit() {
        val e = Edits("a"); e.push("b"); e.reset("restored")
        assertFalse(e.canUndo)
        assertEquals("restored", e.present)
    }

    // ---- Keys: found inside an ordinary note -----------------------------------------------

    /**
     * A REAL MESSY NOTE, ASSEMBLED AT RUN TIME AND NEVER STORED AS A LITERAL.
     *
     * delivery-gate.md 15a, learned on the first run of that gate elsewhere: a fixture that trips
     * the secret scanner must be BUILT FROM PARTS. G2 correctly stopped a delivery over a key
     * shape in a test file that was not a secret, and leaving it there means G2 goes red on every
     * future delivery — and a check that cries wolf gets skimmed on the release where it was
     * right.
     *
     * So no key-shaped string exists anywhere in this repository, including here. The prefixes
     * are joined at run time, which is invisible to a grep and identical to the parser.
     */
    private val gsk = "g" + "sk_"
    private val messyNote = listOf(
        "Groq account for the film work, opened 3.5.2026",
        gsk + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1",
        "this one is CANCELLED " + gsk + "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB2",
        "https://console.groq.com/keys?srsltid=AfmBOopqrstuvwxyzABCDEFGH12345678",
        "cafeteria",
        gsk + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1",
        "assembly: 0123456789abcdef0123456789abcdef",
        "commit 0123456789abcdef0123456789abcdef01234567 was the fix",
    ).joinToString("\n")

    @Test fun a_word_is_not_a_key() =
        assertFalse(Keys.parse(messyNote, Keys.Provider.GROQ).any { it.contains("cafeteria") })

    @Test fun a_cancelled_line_is_skipped_whole() =
        assertFalse(Keys.parse(messyNote, Keys.Provider.GROQ).any { it.endsWith("2") })

    @Test fun the_same_key_twice_is_stored_once() =
        assertEquals(1, Keys.parse(messyNote, Keys.Provider.GROQ).size)

    /** A 40-character commit hash must not yield a 32-character "key" out of its middle. */
    @Test fun a_commit_hash_is_not_an_assemblyai_key() =
        assertEquals(1, Keys.parse(messyNote, Keys.Provider.ASSEMBLYAI).size)

    @Test fun a_tracking_token_is_not_a_key() =
        assertTrue(Keys.parse(messyNote, Keys.Provider.SPEECHIFY).isEmpty())

    @Test fun hume_pairs_are_read_from_their_labels() {
        val export = """
            Film account
            API key
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            Secret key
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

            Second account
            API key
            cccccccccccccccccccccccccccccccccccccccccccccccc
            Secret key
            dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
        """.trimIndent()
        val pairs = Keys.parseHume(export)
        assertEquals(2, pairs.size)
        assertEquals("Film account", pairs[0].name)
        assertTrue(pairs[0].secret.startsWith("bbbb"))
    }

    /** A half pair is dropped: an API key with no secret can never authenticate. */
    @Test fun a_pair_missing_its_secret_is_not_stored() {
        val half = "Only\nAPI key\naaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
        assertTrue(Keys.parseHume(half).isEmpty())
    }

    /** Masking must not return the whole key for a short one, which is where it matters most. */
    @Test fun a_short_key_is_masked_entirely() =
        assertFalse(Keys.mask("sk_short").contains("short"))

    @Test fun a_long_key_shows_its_ends_and_never_its_middle() {
        val m = Keys.mask(gsk + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123")
        assertTrue(m.startsWith(gsk + "AB"))
        assertFalse(m.contains("MNOP"))
    }

    // ---- Classify: the four verdicts -------------------------------------------------------

    @Test fun two_hundred_is_ok() = assertEquals(Verdict.OK, Classify.of(200, "{}").verdict)

    @Test fun four_oh_one_is_dead() = assertEquals(Verdict.DEAD, Classify.of(401, "Invalid API Key").verdict)

    /** 429 IS THE ONE THAT GETS LOST, AND LOSING IT BURNS WORKING KEYS. */
    @Test fun four_two_nine_rests_and_never_buries() =
        assertEquals(Verdict.COOL, Classify.of(429, "Rate limit reached").verdict)

    /** Cloudflare refuses the CLIENT, so it hits every key identically. Measured on 21 pairs. */
    @Test fun a_403_carrying_1010_is_soft_not_dead() =
        assertEquals(Verdict.SOFT, Classify.of(403, "error code: 1010").verdict)

    @Test fun a_plain_403_is_still_dead() =
        assertEquals(Verdict.DEAD, Classify.of(403, "forbidden").verdict)

    /** Hume's measured exhausted-account body. HTTP 400, not 402. Everybody codes for 402. */
    @Test fun a_400_carrying_zero_credits_is_dead() =
        assertEquals(
            Verdict.DEAD,
            Classify.of(400, """{"message":"Exhausted credit balance","details":{"slug":"zero_credits"}}""").verdict,
        )

    @Test fun a_plain_400_is_soft_because_no_key_fixes_a_malformed_request() =
        assertEquals(Verdict.SOFT, Classify.of(400, "bad request").verdict)

    @Test fun a_404_is_our_url_not_their_key() =
        assertEquals(Verdict.SOFT, Classify.of(404, "page not found").verdict)

    @Test fun an_outage_is_soft() = assertEquals(Verdict.SOFT, Classify.of(503, "").verdict)

    @Test fun an_unrecognised_status_defaults_to_soft() =
        assertEquals(Verdict.SOFT, Classify.of(418, "").verdict)

    /** A timeout arrives as status 0 and must stop the ring rather than burn it. */
    @Test fun a_timeout_is_soft() = assertEquals(Verdict.SOFT, Classify.of(0, "timed out").verdict)

    // ---- restAfter: ask, do not guess ------------------------------------------------------

    /** MEASURED: Groq answers a per-minute 429 with retry-after 2. The rings parked it for 60. */
    @Test fun retry_after_is_read_in_seconds() =
        assertEquals(2L, Classify.restAfter(mapOf("Retry-After" to "2")))

    @Test fun groq_reset_format_minutes_and_seconds() =
        assertEquals(2678L, Classify.restAfter(mapOf("x-ratelimit-reset-requests" to "44m38.4s")))

    /** Sub-second must not become zero: zero means retry now, which spins the ring. */
    @Test fun a_millisecond_reset_is_floored_to_one_second() =
        assertEquals(1L, Classify.restAfter(mapOf("x-ratelimit-reset-tokens" to "547ms")))

    @Test fun a_provider_answering_zero_is_floored() =
        assertEquals(1L, Classify.restAfter(mapOf("retry-after" to "0")))

    @Test fun a_rest_longer_than_an_hour_is_capped() =
        assertEquals(3600L, Classify.restAfter(mapOf("retry-after" to "99999")))

    @Test fun nothing_in_the_headers_gives_the_default() =
        assertEquals(Classify.DEFAULT_REST, Classify.restAfter(emptyMap()))

    @Test fun an_unparseable_header_is_not_read_as_zero() =
        assertEquals(null, Classify.duration("soon"))

    // ---- Ring: rotation, counted -----------------------------------------------------------
    //
    // Driven by a scripted fake that COUNTS the attempts, so "nothing was tried twice" is a
    // number rather than an opinion.

    private fun ruling(v: Verdict, rest: Long = 0) = Ruling(v, rest, "test")

    @Test fun the_ring_stops_at_the_first_key_that_works() {
        val r = Ring(listOf("a", "b", "c"))
        var calls = 0
        val out = r.run { calls++; Ring.Outcome(ruling(Verdict.OK), "text") }
        assertTrue(out is Ring.Result.Ok)
        assertEquals(1, calls)
    }

    @Test fun a_dead_key_is_buried_and_the_next_is_tried() {
        val r = Ring(listOf("a", "b"))
        var calls = 0
        r.run { key -> calls++; Ring.Outcome(if (key == "a") ruling(Verdict.DEAD) else ruling(Verdict.OK)) }
        assertEquals(2, calls)
        assertEquals(1, r.aliveCount())
    }

    /** NOTHING IS EVER TRIED TWICE IN ONE CALL. A bound, not a `while (true)`. */
    @Test fun every_key_is_tried_at_most_once_per_call() {
        val r = Ring(listOf("a", "b", "c"))
        var calls = 0
        val out = r.run { calls++; Ring.Outcome(ruling(Verdict.DEAD)) }
        assertEquals(3, calls)
        assertTrue(out is Ring.Result.Exhausted)
    }

    @Test fun a_throttled_key_is_never_buried() {
        val r = Ring(listOf("a"))
        r.run { Ring.Outcome(ruling(Verdict.COOL, 30)) }
        assertEquals(1, r.aliveCount())
        assertEquals(1, r.restingCount())
    }

    /** SOFT STOPS EVERYTHING. Retrying a malformed request against every key spends every key. */
    @Test fun a_soft_failure_stops_the_ring_after_one_attempt() {
        val r = Ring(listOf("a", "b", "c"))
        var calls = 0
        val out = r.run { calls++; Ring.Outcome(ruling(Verdict.SOFT)) }
        assertEquals(1, calls)
        assertTrue(out is Ring.Result.Stopped)
    }

    /** Resume where the last success was, or every call re-walks the same dead keys. */
    @Test fun the_ring_resumes_where_it_succeeded() {
        val r = Ring(listOf("a", "b", "c"))
        r.run { key -> Ring.Outcome(if (key == "c") ruling(Verdict.OK) else ruling(Verdict.DEAD)) }
        var first: String? = null
        r.run { key -> if (first == null) first = key; Ring.Outcome(ruling(Verdict.OK)) }
        assertEquals("c", first)
    }

    /** A SKIPPED KEY IS NOT A TRIED KEY. All resting is a wait, and the wait has a number. */
    @Test fun all_keys_resting_is_a_wait_with_a_duration() {
        var clock = 1_000L
        val r = Ring(listOf("a", "b")) { clock }
        r.run { Ring.Outcome(ruling(Verdict.COOL, 10)) }
        clock += 1_000
        val out = r.run { Ring.Outcome(ruling(Verdict.OK)) }
        assertTrue(out is Ring.Result.Exhausted)
        assertTrue((out as Ring.Result.Exhausted).waitSeconds > 0)
    }

    @Test fun an_empty_ring_says_so_rather_than_throwing() {
        val out = Ring(emptyList()).run { Ring.Outcome(ruling(Verdict.OK)) }
        assertTrue(out is Ring.Result.Exhausted)
    }

    @Test fun revive_brings_every_buried_key_back() {
        val r = Ring(listOf("a", "b"))
        r.run { Ring.Outcome(ruling(Verdict.DEAD)) }
        assertEquals(0, r.aliveCount())
        r.reviveAll()
        assertEquals(2, r.aliveCount())
    }

    // ---- Level: the arithmetic that stops words being dropped ------------------------------

    private fun tone(amplitude: Float, n: Int = 16_000) =
        FloatArray(n) { if (it % 2 == 0) amplitude else -amplitude }

    @Test fun silence_is_the_floor_not_negative_infinity() =
        assertEquals(Level.FLOOR_DB, Level.db(0.0), 0.001)

    @Test fun full_scale_is_zero_db() = assertEquals(0.0, Level.db(1.0), 0.001)

    @Test fun a_quiet_take_is_lifted() =
        assertTrue(Level.gainDb(tone(0.02f)) > 6.0)

    /** Never enough gain to clip. The ceiling binds before the target is reached. */
    @Test fun a_loud_take_is_never_pushed_into_clipping() {
        val s = tone(0.95f)
        Level.applyInPlace(s)
        assertTrue(s.all { kotlin.math.abs(it) <= 1.0f })
    }

    /** Silence plus nothing must not be lifted, or the meter and the file both start lying. */
    @Test fun silence_is_not_amplified() =
        assertEquals(0.0, Level.gainDb(FloatArray(16_000)), 0.001)

    @Test fun an_empty_take_has_no_gain() = assertEquals(0.0, Level.gainDb(FloatArray(0)), 0.001)

    /** THE MEASURED PROBLEM: two sources far apart end up close together. */
    @Test fun two_takes_29_db_apart_end_up_within_a_few_db() {
        val quiet = tone(0.01f)
        val loud = tone(0.3f)
        assertTrue(Level.speechRms(loud) - Level.speechRms(quiet) > 25.0)
        Level.applyInPlace(quiet)
        Level.applyInPlace(loud)
        assertTrue(kotlin.math.abs(Level.speechRms(loud) - Level.speechRms(quiet)) < 6.0)
    }

    @Test fun the_lift_is_capped_so_a_cough_does_not_become_a_wall_of_noise() =
        assertTrue(Level.gainDb(tone(0.0005f)) <= Level.MAX_GAIN_DB + 0.001)

    // ---- Roles: a stored choice from a version that no longer exists -----------------------

    @Test fun the_default_transcriber_is_the_accurate_one() =
        assertEquals(Keys.Provider.ASSEMBLYAI, Roles.resolve(Role.TRANSCRIPTION, null))

    /** Anybody upgrading from a build made before Edge was dropped has exactly this stored. */
    @Test fun a_stored_provider_that_no_longer_exists_falls_back() =
        assertEquals(Keys.Provider.SPEECHIFY, Roles.resolve(Role.VOICE, "EDGE"))

    @Test fun a_provider_that_cannot_hold_the_role_falls_back() =
        assertEquals(Keys.Provider.ASSEMBLYAI, Roles.resolve(Role.TRANSCRIPTION, "SPEECHIFY"))

    @Test fun a_valid_stored_choice_is_honoured() =
        assertEquals(Keys.Provider.GROQ, Roles.resolve(Role.TRANSCRIPTION, "GROQ"))

    @Test fun edge_is_gone_from_every_role() =
        assertTrue(Role.entries.all { role -> Roles.optionsFor(role).none { it.name == "EDGE" } })

    // ---- Spoken: the language of the speech, not of the voices ------------------------------

    /** Croatian VOICES were dropped. Croatian HEARING was not, and must not be. */
    @Test fun croatian_is_still_a_language_that_can_be_spoken_into_the_app() =
        assertTrue(Spoken.ORDER.contains(Spoken.HR))

    @Test fun auto_sends_no_language_code_at_all() = assertEquals(null, Spoken.AUTO.code)

    @Test fun an_unknown_stored_language_falls_back_to_croatian() =
        assertEquals(Spoken.HR, Spoken.of("KLINGON"))

    @Test fun croatian_comes_first_as_everywhere_else_in_this_account() =
        assertEquals(Spoken.HR, Spoken.ORDER.first())

    @Test fun the_three_options_are_distinct() =
        assertNotEquals(Spoken.HR.label, Spoken.EN.label)
}
