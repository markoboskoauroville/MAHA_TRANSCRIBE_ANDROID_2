package com.mantra.mahatranscribe.net

import com.mantra.mahatranscribe.logic.Classify
import com.mantra.mahatranscribe.logic.Keys
import com.mantra.mahatranscribe.logic.Ring
import com.mantra.mahatranscribe.logic.Spoken
import com.mantra.mahatranscribe.logic.Verdict
import org.json.JSONObject
import java.io.File

/**
 * SPEECH TO TEXT, TWO PROVIDERS, ONE SHAPE.
 *
 * Each provider exposes exactly one function: given a key and a file, do the WHOLE job and come
 * back with either text or a ruling. That shape is not decoration, it is keyring.md 2b: a ring
 * rotates between jobs and never between the calls inside one job, because AssemblyAI's upload
 * belongs to the account that made it. Doing otherwise answered `403 Cannot access uploaded
 * file`, which was read as a dead key, and one clip walked through six good accounts and buried
 * all six.
 *
 * So the AssemblyAI path below does upload, submit and poll on ONE key, and if that key dies the
 * ring restarts the whole job on the next one rather than continuing it.
 */
object Transcribe {

    /** What the caller gets. A failure is a sentence, never an exception and never silence. */
    sealed class Outcome {
        data class Text(val text: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * Run [file] through whichever provider holds the transcription role.
     *
     * [ring] belongs to that provider. [language] null means AUTO, in which case no language code
     * is sent at all rather than a guess being sent.
     */
    fun run(
        provider: Keys.Provider,
        ring: Ring,
        file: File,
        language: Spoken,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        val result = when (provider) {
            Keys.Provider.GROQ -> ring.run { key -> groq(key, file, language) }
            Keys.Provider.ASSEMBLYAI -> ring.run { key -> assemblyAi(key, file, language, onProgress) }
            else -> return Outcome.Failed("$provider cannot transcribe.")
        }
        return when (result) {
            is Ring.Result.Ok -> Outcome.Text(result.body)
            is Ring.Result.Stopped -> Outcome.Failed(result.why)
            is Ring.Result.Exhausted ->
                if (result.waitSeconds > 0)
                    Outcome.Failed("${result.why}. Try again in ${result.waitSeconds} seconds.")
                else Outcome.Failed(result.why)
        }
    }

    // ---- GROQ ------------------------------------------------------------------------------
    //
    // One multipart POST and the answer comes back in it. The fast path.

    private const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

    /** The fast model. `verbose_json` carries both `language` and `text` in one response. */
    const val GROQ_MODEL = "whisper-large-v3-turbo"

    private fun groq(key: String, file: File, language: Spoken): Ring.Outcome {
        val fields = mutableMapOf("model" to GROQ_MODEL, "response_format" to "verbose_json")
        // AUTO sends nothing. An empty language field is not the same as an absent one, and
        // "unset" is what tells Whisper to decide for itself.
        language.code?.let { fields["language"] = it }
        val r = Http.multipart(
            GROQ_URL, mapOf("Authorization" to "Bearer $key"),
            file, "file", "audio/wav", fields,
        )
        val ruling = Classify.of(r.status, r.body, r.headers)
        if (ruling.verdict != Verdict.OK) return Ring.Outcome(ruling)
        val text = runCatching { JSONObject(r.body).optString("text", "") }.getOrDefault("")
        return Ring.Outcome(Ruling200, text)
    }

    // ---- ASSEMBLYAI ------------------------------------------------------------------------
    //
    // Upload, submit, poll. Three calls, one key, and the key is chosen once by the ring before
    // the first of them.

    private const val AAI = "https://api.assemblyai.com/v2"

    /**
     * How long the poll is allowed to run before it gives up, and how often it asks.
     *
     * G5: every wait outside the process has a deadline and every loop has a fixed upper bound
     * that can be read off the page. `MAX_POLLS * POLL_MS` is twenty minutes, which is longer
     * than any single take this app encourages, because MULTI mode exists precisely so that
     * nobody records for twenty minutes at once.
     */
    private const val POLL_MS = 3_000L
    private const val MAX_POLLS = 400

    private fun assemblyAi(
        key: String,
        file: File,
        language: Spoken,
        onProgress: (String) -> Unit,
    ): Ring.Outcome {
        // AssemblyAI takes the RAW key with no Bearer, which is unusual and easy to get wrong.
        // A 401 on a good-looking key here usually means Bearer was sent.
        val auth = mapOf("authorization" to key)

        onProgress("sending the audio")
        val up = Http.call("$AAI/upload", "POST", auth, "application/octet-stream") { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        Classify.of(up.status, up.body, up.headers).let { if (it.verdict != Verdict.OK) return Ring.Outcome(it) }
        val audioUrl = runCatching { JSONObject(up.body).optString("upload_url", "") }.getOrDefault("")
        if (audioUrl.isEmpty()) return Ring.Outcome(SoftRuling("the upload returned no address"))

        onProgress("transcribing")
        val body = JSONObject().put("audio_url", audioUrl).apply {
            language.code?.let { put("language_code", it) }
            if (language.code == null) put("language_detection", true)
        }
        val sub = Http.call("$AAI/transcript", "POST", auth, "application/json") { out ->
            out.write(body.toString().toByteArray())
        }
        Classify.of(sub.status, sub.body, sub.headers).let { if (it.verdict != Verdict.OK) return Ring.Outcome(it) }
        val id = runCatching { JSONObject(sub.body).optString("id", "") }.getOrDefault("")
        if (id.isEmpty()) return Ring.Outcome(SoftRuling("the job was accepted without an id"))

        // A BOUNDED loop, and the bound is visible. Never `while (true)`.
        for (attempt in 1..MAX_POLLS) {
            Thread.sleep(POLL_MS)
            val p = Http.call("$AAI/transcript/$id", "GET", auth)
            val ruling = Classify.of(p.status, p.body, p.headers)
            if (ruling.verdict != Verdict.OK) return Ring.Outcome(ruling)

            // TRAP 3: A FAILURE THAT ARRIVES AS 200. Any submit-then-poll provider can do this —
            // the HTTP call succeeded and the WORK failed, and those are different questions.
            // Measured: `{"status":"error","error":"Download error...","audio_duration":0}` with
            // an HTTP 200 around it. Read the status INSIDE the body before believing a 200.
            val json = runCatching { JSONObject(p.body) }.getOrNull()
                ?: return Ring.Outcome(SoftRuling("the provider sent something that is not JSON"))
            when (json.optString("status")) {
                "completed" -> return Ring.Outcome(Ruling200, json.optString("text", ""))
                "error" -> {
                    val why = json.optString("error", "the transcription failed")
                    // The job failed, and that is not the same as the key being bad. Unless the
                    // body says credit, in which case it is the account and the ring should move.
                    return Ring.Outcome(Classify.of(400, why, emptyMap()).let {
                        if (it.verdict == Verdict.DEAD) it else SoftRuling(why)
                    })
                }
                else -> onProgress("transcribing, ${attempt * POLL_MS / 1000}s")
            }
        }
        return Ring.Outcome(SoftRuling("the transcription did not finish within twenty minutes"))
    }

    private val Ruling200 = com.mantra.mahatranscribe.logic.Ruling(Verdict.OK)
    private fun SoftRuling(why: String) = com.mantra.mahatranscribe.logic.Ruling(Verdict.SOFT, 0, why)
}
