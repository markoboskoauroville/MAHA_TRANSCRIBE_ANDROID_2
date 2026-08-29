package com.mantra.mahatranscribe.logic

/**
 * MANY KEYS, TRIED IN ORDER, THE DEAD ONES SET ASIDE, THE WORKING ONE REMEMBERED.
 *
 * keyring.md 1: Baba holds ten or more keys for a provider at once and they run out one at a
 * time. An app that stores one key stops dead the moment that key finishes. An app that tries
 * all of them burns a call on every finished key on every request. So: a ring. It walks forward,
 * never backwards into a key it has already buried, and writes down where it got to.
 *
 * ### The rule that matters most
 *
 * > **Never test a key speculatively.**
 *
 * Use the first key not known dead and let a real request find out. A dead key should cost ONE
 * wasted call in its entire life. The mistake this replaces is the "check all keys on startup"
 * screen, which spends N calls to learn what the next real request would have said for free.
 *
 * ### ONE KEY PER JOB, NEVER ONE KEY PER CALL
 *
 * keyring.md 2b, and it cost an evening. A ring rotates between JOBS. It must never rotate
 * between the calls inside one job, because most providers hold state between calls and that
 * state belongs to the account that created it. A transcription tool asked the ring for a key on
 * every API call, so a file was uploaded on account A and submitted on account B; AssemblyAI
 * answered `403 Cannot access uploaded file`; the tool read every 403 as a dead key, and one
 * clip walked through six good accounts and marked all six dead.
 *
 * That is why [run] hands the attempt a KEY and expects the attempt to perform the WHOLE job —
 * upload, submit, poll, download — on that one key. It is also why moving to another key
 * restarts the job rather than continuing it: the new account cannot see what the old one
 * uploaded.
 *
 * This class is pure. It knows nothing about HTTP, Android or any provider, which is what lets
 * Test 1 drive it with a scripted fake that returns a chosen verdict per attempt and COUNTS the
 * attempts, so "nothing was tried twice" becomes a number rather than an opinion.
 */
class Ring(keys: List<String>, private val now: () -> Long = { System.currentTimeMillis() }) {

    /** One key and what is currently known about it. */
    data class Slot(
        val key: String,
        var dead: Boolean = false,
        var coolUntil: Long = 0,
        var lastError: String = "",
    )

    val slots: List<Slot> = keys.map { Slot(it) }

    /**
     * Where the last success was. Resume here rather than at zero: starting at zero walks the
     * same dead keys on every call, which is a cost that grows as the ring ages.
     */
    var active: Int = 0
        private set

    /** What an attempt reports back. [billed] is optional and only used for the log. */
    data class Outcome(val ruling: Ruling, val body: String = "", val billed: Long = 0)

    /** What [run] concluded, for the screen. Never an exception: a failure is a sentence. */
    sealed class Result {
        data class Ok(val body: String, val key: String) : Result()

        /** Something we did wrong, or the provider is down. The ring stopped on purpose. */
        data class Stopped(val why: String) : Result()

        /** Every key is dead or resting. [waitSeconds] is 0 when they are all genuinely dead. */
        data class Exhausted(val why: String, val waitSeconds: Long) : Result()
    }

    fun aliveCount(): Int = slots.count { !it.dead }
    fun restingCount(): Int = slots.count { !it.dead && it.coolUntil > now() }

    /**
     * Run one whole job, rotating only when a key is genuinely gone.
     *
     * Six rules that are easy to get wrong, all from quota-and-fallback.md 5, all enforced here:
     *
     *  1. Try each key AT MOST ONCE per call. A bounded loop over the size, never `while(true)`.
     *  2. Resume where the last success was, not at zero.
     *  3. A SKIPPED KEY IS NOT A TRIED KEY. If every key is resting that is a wait, not a
     *     failure, and the caller is told which and for how long.
     *  4. Rolling forward is invisible: the person sees one slightly slower response, ever.
     *  5. SOFT stops everything.
     *  6. A 429 rests and never buries.
     */
    fun run(attempt: (String) -> Outcome): Result {
        if (slots.isEmpty()) return Result.Exhausted("there are no keys yet", 0)

        val t = now()
        var tried = 0
        var soonestWake = Long.MAX_VALUE
        var lastWhy = ""

        for (step in slots.indices) {
            val i = (active + step) % slots.size
            val slot = slots[i]
            if (slot.dead) continue
            if (slot.coolUntil > t) {
                soonestWake = minOf(soonestWake, slot.coolUntil)
                continue
            }

            tried++
            val outcome = attempt(slot.key)
            when (outcome.ruling.verdict) {
                Verdict.OK -> {
                    slot.lastError = ""
                    active = i
                    return Result.Ok(outcome.body, slot.key)
                }
                Verdict.DEAD -> {
                    slot.dead = true
                    slot.lastError = outcome.ruling.why
                    lastWhy = outcome.ruling.why
                }
                Verdict.COOL -> {
                    val rest = outcome.ruling.restSeconds.coerceAtLeast(Classify.MIN_REST)
                    slot.coolUntil = t + rest * 1000
                    slot.lastError = outcome.ruling.why
                    soonestWake = minOf(soonestWake, slot.coolUntil)
                    lastWhy = outcome.ruling.why
                }
                Verdict.SOFT -> return Result.Stopped(outcome.ruling.why)
            }
        }

        // Nothing was tried at all, so nothing is broken: they are all asleep. Say so, and say
        // for how long, because a ring silently walking twenty dead keys looks identical to one
        // that is simply slow.
        if (tried == 0 && soonestWake != Long.MAX_VALUE) {
            val secs = ((soonestWake - t) / 1000).coerceAtLeast(1)
            return Result.Exhausted("every key is resting", secs)
        }
        val wait = if (soonestWake == Long.MAX_VALUE) 0 else ((soonestWake - t) / 1000).coerceAtLeast(1)
        return Result.Exhausted(
            if (lastWhy.isEmpty()) "all ${slots.size} keys are unavailable"
            else "all ${slots.size} keys are unavailable, last: $lastWhy",
            wait,
        )
    }

    /**
     * Bring every buried key back.
     *
     * keyring.md 6: credit gets topped up, and a permanent condemnation that cannot be undone is
     * a bug wearing a rule's clothing.
     */
    fun reviveAll() {
        slots.forEach { it.dead = false; it.coolUntil = 0; it.lastError = "" }
    }
}
