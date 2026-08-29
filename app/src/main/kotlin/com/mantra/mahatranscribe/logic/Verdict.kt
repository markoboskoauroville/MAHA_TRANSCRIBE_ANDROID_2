package com.mantra.mahatranscribe.logic

/**
 * WHAT A PROVIDER'S ANSWER MEANS, AND THERE ARE ONLY FOUR ANSWERS.
 *
 * quota-and-fallback.md 1, and it is the one sentence that matters:
 *
 * > **A status code alone cannot tell you whether to bury a key, rest it, or blame yourself.
 * > Read the body. Read the headers.**
 *
 * Every fallback bug found on 25.8.2026 — in four separate rings, written months apart — was the
 * same bug: a classifier whose signature was `(status: Int)`. This one takes three arguments and
 * there is exactly ONE of it in the app. Not one per provider. Every fault in that audit existed
 * because each ring kept its own copy, so a lesson paid for on one never reached the others.
 */
enum class Verdict {
    /** It worked. Use this key, keep it first. */
    OK,

    /** This key is finished. Bury it, try the next. */
    DEAD,

    /** This key is fine and tired. Park it with a clock, try the next. NEVER bury. */
    COOL,

    /**
     * OUR fault, not the key's. Stop the whole ring.
     *
     * Stopping everything is correct: retrying a malformed request against twenty more keys
     * spends twenty more calls to be told the same thing. It is also why misclassifying a real
     * failure as SOFT is the expensive mistake, and two genuine failures were landing here.
     */
    SOFT,
}

/** What the classifier decided, and how long to rest if it decided [Verdict.COOL]. */
data class Ruling(val verdict: Verdict, val restSeconds: Long = 0, val why: String = "")

object Classify {

    /**
     * Substrings that mean the account is empty, matched in a LOWERCASED body.
     *
     * Every provider picks different ones, which is why this is a list rather than a status code.
     * `zero_credits` and `e0300` are Hume's, measured 25.8.2026 on a genuinely exhausted account.
     */
    private val CREDIT_WORDS = listOf(
        "zero_credits", "e0300", "credit balance", "insufficient", "quota exceeded",
        "out of credits", "payment required", "billing",
    )

    /**
     * The whole mapping, in the order the branches must appear.
     *
     * The 1010 branch comes FIRST and a test asserts its position by index rather than by
     * presence. TTT mini's audit found that a branch in the right file in the wrong place is the
     * same bug: `DictateApiException.fromHttp` classified every 403 as a bad key, so one request
     * without a User-Agent would have returned 403 to every key identically and **buried the
     * entire ring in a single pass**, recoverable only by editing the store by hand.
     */
    fun of(status: Int, body: String, headers: Map<String, String> = emptyMap()): Ruling {
        val lower = body.lowercase()

        // TRAP 1. api.groq.com and api.hume.ai both sit behind Cloudflare. It refuses the CLIENT,
        // so it hits every key identically. Measured on Hume: 21 pairs, all 21 returned 403/1010
        // with no User-Agent and all 21 returned 200 with any User-Agent at all.
        if (status == 403 && "1010" in lower) {
            return Ruling(Verdict.SOFT, 0, "Cloudflare refused the client, not the key. Check the User-Agent.")
        }

        if (status in 200..299) return Ruling(Verdict.OK)

        if (status == 401 || status == 402 || status == 403) {
            return Ruling(Verdict.DEAD, 0, "the provider refused this key")
        }

        if (status == 429) {
            // TRAP 4. Per-minute and per-day wear the same status and the same code. Only the
            // message text and the reset window separate them, so ASK how long rather than
            // choosing a number: a fixed sixty seconds retries a key that is out for the day,
            // every minute, all day. Measured: Groq answers a per-minute 429 with retry-after 2,
            // and the rings parked the key for sixty.
            return Ruling(Verdict.COOL, restAfter(headers), "valid and throttled")
        }

        if (status == 400) {
            // TRAP 2. An empty account that does not say 402. Hume answers HTTP 400 with
            // zero_credits in the body. Everybody codes for 402; Hume does not send it. On
            // status alone this landed in SOFT and stopped the run, so one empty account out of
            // twenty-one killed the feature and the other twenty were never tried.
            if (CREDIT_WORDS.any { it in lower }) {
                return Ruling(Verdict.DEAD, 0, "the account is out of credit")
            }
            return Ruling(Verdict.SOFT, 0, "the request was malformed; no key fixes that")
        }

        if (status == 404) return Ruling(Verdict.SOFT, 0, "wrong endpoint, not a bad key")
        if (status >= 500) return Ruling(Verdict.SOFT, 0, "the provider is having an outage")

        // DEFAULT TO SOFT. An unknown failure is one nobody has classified yet, and stopping is
        // cheaper than burning a ring on a guess.
        return Ruling(Verdict.SOFT, 0, "unrecognised response $status")
    }

    /** Lower bound on a rest, because a provider answering 0 would spin the ring. */
    const val MIN_REST = 1L

    /**
     * Upper bound, because a key parked longer than this is indistinguishable from a dead one to
     * somebody waiting. Better to try it and be told no a second time.
     */
    const val MAX_REST = 3600L

    /** What to do when nothing in the response says how long. */
    const val DEFAULT_REST = 60L

    /**
     * How long the provider ASKED to be left alone, in seconds.
     *
     * Reads `Retry-After` first, then any `x-ratelimit-reset-*` header. Groq publishes the reset
     * windows in a duration format of its own — `44m38.4s`, `547ms`, `2` — so that format is
     * parsed here rather than assumed to be a plain number of seconds.
     */
    fun restAfter(headers: Map<String, String>): Long {
        val lower = headers.mapKeys { it.key.lowercase() }
        lower["retry-after"]?.let { v -> duration(v)?.let { return clamp(it) } }
        for ((k, v) in lower) {
            if (k.startsWith("x-ratelimit-reset")) duration(v)?.let { return clamp(it) }
        }
        return DEFAULT_REST
    }

    private fun clamp(s: Long) = s.coerceIn(MIN_REST, MAX_REST)

    /**
     * `"2"` -> 2, `"547ms"` -> 1 (floored), `"44m38.4s"` -> 2678, `"1h"` -> 3600.
     *
     * Returns null when nothing numeric is present, so the caller can fall through rather than
     * treat an unparseable header as zero. Zero would mean "retry immediately", which is the
     * opposite of what a throttling header is telling you, and it is the shape of bug that spins.
     */
    fun duration(raw: String): Long? {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return null
        Regex("""^\d+$""").find(t)?.let { return it.value.toLong() }
        var total = 0.0
        var found = false
        // Milliseconds first: "547ms" also matches the seconds pattern on its leading digits if
        // the units are read in the wrong order, and that turns half a second into 547 seconds.
        for (m in Regex("""(\d+(?:\.\d+)?)\s*(ms|h|m|s)""").findAll(t)) {
            val n = m.groupValues[1].toDouble()
            total += when (m.groupValues[2]) {
                "ms" -> n / 1000.0
                "s" -> n
                "m" -> n * 60
                "h" -> n * 3600
                else -> 0.0
            }
            found = true
        }
        if (!found) return null
        // Floor, then the caller clamps to at least one second. A sub-second rest rounds to zero
        // and zero spins.
        return total.toLong()
    }
}
