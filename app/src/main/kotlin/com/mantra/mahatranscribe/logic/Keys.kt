package com.mantra.mahatranscribe.logic

/**
 * FINDING KEYS INSIDE ORDINARY HANDWRITTEN NOTES.
 *
 * keyring.md 4: the key file is a working note. It has dates in it, account names, the word
 * CANCELLED, export lines, URLs with tracking parameters and blank lines. A parser that treats a
 * line as a key is useless against that. A naive whitespace split on a real note has genuinely
 * produced attempts to authenticate with the word *cafeteria* and with a Google `srsltid`
 * tracking token.
 *
 * So: find keys by SHAPE and leave everything else alone.
 *
 *     Speechify    sk_ + about 46 characters
 *     Groq         gsk_ + alphanumeric
 *     AssemblyAI   32 hex characters, NO PREFIX — the awkward one
 *     Anthropic    sk-ant-
 *     GitHub       ghp_ / github_pat_
 *
 * ### The awkward case, stated plainly
 *
 * A key that is bare hex cannot be told from a commit hash or a colour by shape alone. It needs
 * context: the provider being asked for. So [parse] takes the provider rather than guessing, and
 * that is honest rather than clever.
 *
 * ### The asymmetry that leaked a key, and why it is not repeated here
 *
 * secrets.md 2a: a shape pattern is safe for EXTRACTION and unsafe for REDACTION. Extraction
 * fails closed — an unrecognised key is not stored, the count is zero, and you find out in
 * seconds. Redaction fails open — an unrecognised key is not replaced and IS printed, under a
 * header saying it was redacted. **There is no redaction function in this file and there must
 * never be one.** Masking for display is [mask], which shows a fixed number of characters from
 * each end of a string it is already holding, and cannot leak a format it has not seen.
 */
object Keys {

    enum class Provider { GROQ, ASSEMBLYAI, SPEECHIFY, HUME, ANTHROPIC }

    private val SHAPES: Map<Provider, Regex> = mapOf(
        Provider.GROQ to Regex("""gsk_[A-Za-z0-9]{20,}"""),
        Provider.SPEECHIFY to Regex("""sk_[A-Za-z0-9_-]{30,}"""),
        Provider.ANTHROPIC to Regex("""sk-ant-[A-Za-z0-9_-]{20,}"""),
        // Bare hex. Anchored to word boundaries so a 40-character commit hash does not yield a
        // 32-character "key" out of its middle.
        Provider.ASSEMBLYAI to Regex("""\b[0-9a-f]{32}\b"""),
    )

    /**
     * Every key of [provider]'s shape in [blob], in the order they appear, de-duplicated.
     *
     * De-duplication keeps the FIRST appearance. The same key arrives twice from two files often
     * enough to be worth one line of code, and a ring holding the same dead key twice wastes two
     * calls instead of one.
     *
     * Lines containing DELETED or CANCELLED are dropped whole, because a note that says a key is
     * gone is better evidence than the fact that its characters are still on the page.
     */
    fun parse(blob: String, provider: Provider): List<String> {
        val shape = SHAPES[provider] ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (line in blob.lineSequence()) {
            val upper = line.uppercase()
            if ("DELETED" in upper || "CANCELLED" in upper || "CANCELED" in upper) continue
            for (m in shape.findAll(line)) out.add(m.value)
        }
        return out.toList()
    }

    /**
     * One Hume account: an API key and a Secret key that belong together.
     *
     * apis/hume.md: unlike every other provider here a Hume credential is a PAIR, and both halves
     * are plain alphanumeric with NO PREFIX (API about 48 characters, Secret about 63 — measured
     * 64 in Baba's own export on 29.8.2026). Shape cannot tag them. **The labels are the only
     * reliable signal**, so this parser is line-aware rather than shape-aware, and it is the one
     * exception in this file.
     */
    data class HumePair(val name: String, val apiKey: String, val secret: String)

    /**
     * Parse the Hume dashboard export.
     *
     * The format, per account:
     *
     *     <account name>
     *     API key
     *     <api key>
     *     Secret key
     *     <secret key>
     *
     * On a line equal to `API key` take the next non-empty line as the API key, find the
     * following `Secret key` label and take ITS next non-empty line as the secret, and use the
     * nearest non-empty line ABOVE `API key` as the account name.
     *
     * A pair missing either half is DROPPED rather than half-stored. Storing an API key with no
     * secret would put an account in the ring that can never authenticate, and it would look
     * like a dead key rather than like a parse failure.
     */
    fun parseHume(blob: String): List<HumePair> {
        val lines = blob.lines()
        fun nextNonEmpty(from: Int): Pair<Int, String>? {
            for (j in from + 1 until lines.size) {
                val t = lines[j].trim()
                if (t.isNotEmpty()) return j to t
            }
            return null
        }
        fun prevNonEmpty(from: Int): String {
            for (j in from - 1 downTo 0) {
                val t = lines[j].trim()
                if (t.isNotEmpty()) return t
            }
            return ""
        }

        val out = LinkedHashMap<String, HumePair>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().equals("API key", ignoreCase = true)) {
                val api = nextNonEmpty(i)
                val name = prevNonEmpty(i)
                var secret: String? = null
                var k = api?.first ?: i
                while (k < lines.size) {
                    if (lines[k].trim().equals("Secret key", ignoreCase = true)) {
                        secret = nextNonEmpty(k)?.second
                        break
                    }
                    k++
                }
                if (api != null && secret != null) {
                    // De-duplicate on the API key, first appearance wins, exactly as above.
                    out.putIfAbsent(api.second, HumePair(name, api.second, secret))
                }
                i = k
            }
            i++
        }
        return out.values.toList()
    }

    /**
     * A key as it may appear on a screen: first six characters, last four, never the middle.
     *
     * keyring.md 6. A key shorter than the two windows together is shown as stars only — the
     * naive version returns the whole key for a short string, which is the one input where
     * masking matters most because it is the one where a mistake is invisible.
     */
    fun mask(key: String): String {
        if (key.length <= 10) return "*".repeat(key.length.coerceAtLeast(4))
        return key.take(6) + "…" + key.takeLast(4)
    }
}
