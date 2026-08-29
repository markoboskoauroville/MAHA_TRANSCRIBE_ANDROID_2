package com.mantra.mahatranscribe.net

import com.mantra.mahatranscribe.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * EVERY REQUEST THIS APP MAKES GOES THROUGH HERE, AND THERE ARE TWO REASONS.
 *
 * ### 1. The User-Agent is not optional
 *
 * apis/README.md, and it is measured twice on two hosts. `api.groq.com` and `api.hume.ai` both
 * sit behind Cloudflare, which refuses a request with NO User-Agent and answers `403, error
 * code: 1010` on every endpoint including the cheapest one. Measured on Hume: 21 account pairs,
 * all 21 returned 403 with no UA and all 21 returned 200 with any UA at all.
 *
 * **This looks exactly like every key being dead and is nothing to do with the keys.** A ring
 * that reads it as death buries everything in one pass and the only way back is editing the
 * store by hand. [Classify][com.mantra.mahatranscribe.logic.Classify] catches it as a second
 * line of defence, but not being refused is better than recovering from being refused, so the
 * header is set HERE, once, where no provider can forget it.
 *
 * A descriptive string works as well as a browser one, verified side by side, so this app says
 * what it is rather than impersonating Chrome. Impersonating a browser to an API you
 * authenticate to honestly is unnecessary and ages badly.
 *
 * ### 2. Every wait outside this process has a deadline
 *
 * G5 of the delivery gate. A call with no timeout has the library's default, which for
 * `HttpURLConnection` is FOREVER. The worst bug this app's ancestor ever had was a button that
 * did nothing, and its cause was exactly this shape: a call that never answered, so the failure
 * branch was never reached and the fallback never appeared. People conclude the app is broken.
 *
 * There is no `while (true)` anywhere in this file and no wait without a number beside it.
 */
object Http {

    /** Descriptive, honest, and enough to satisfy Cloudflare. */
    val UA = "MahaTranscribe/${BuildConfig.VERSION_NAME} (Android; Mantra Productions)"

    /** Long enough for a slow phone network, short enough that somebody is not left staring. */
    const val CONNECT_MS = 15_000
    const val READ_MS = 120_000

    /** What came back. [body] is always a string, even for a failure, because the body is read. */
    data class Reply(val status: Int, val body: String, val headers: Map<String, String>)

    /**
     * One request. [write] streams the body, or null for a GET.
     *
     * The error stream is read on failure. A classifier that cannot see the body cannot tell an
     * empty Hume account from a malformed request, and that is the distinction the whole ring
     * rests on.
     */
    fun call(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        write: ((java.io.OutputStream) -> Unit)? = null,
    ): Reply {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (write != null) doOutput = true
        }
        try {
            write?.let { w -> c.outputStream.use { w(it) } }
            val status = c.responseCode
            val stream: InputStream? = if (status in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hdrs = c.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull() ?: "" }
            return Reply(status, body, hdrs)
        } catch (e: java.net.SocketTimeoutException) {
            // A deadline that fires is the feature. Reported as a status of 0 so the classifier
            // calls it SOFT and the ring stops rather than burning keys on a slow network.
            return Reply(0, "the request timed out after ${READ_MS / 1000} seconds", emptyMap())
        } catch (e: java.io.IOException) {
            return Reply(0, e.message ?: "no network", emptyMap())
        } finally {
            c.disconnect()
        }
    }

    /** Bytes fetched with the same deadlines. Used for audio coming back from a voice provider. */
    fun bytes(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            return status to out.toByteArray()
        } catch (e: java.io.IOException) {
            return 0 to ByteArray(0)
        } finally {
            c.disconnect()
        }
    }

    /** A multipart body with one file and some plain fields. Streamed, never held twice in memory. */
    fun multipart(
        url: String,
        headers: Map<String, String>,
        file: File,
        fileField: String,
        fileMime: String,
        fields: Map<String, String>,
    ): Reply {
        val boundary = "----maha" + System.nanoTime()
        return call(url, "POST", headers, "multipart/form-data; boundary=$boundary") { out ->
            val w = out.bufferedWriter()
            for ((k, v) in fields) {
                w.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            w.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; " +
                    "filename=\"${file.name}\"\r\nContent-Type: $fileMime\r\n\r\n"
            )
            w.flush()
            file.inputStream().use { it.copyTo(out) }
            out.flush()
            w.write("\r\n--$boundary--\r\n")
            w.flush()
        }
    }
}
