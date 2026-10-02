package hr.exel.kenosis_plugin_internet

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException

/**
 * Pure (JVM-testable) classification of a both-paths-failed fetch error.
 *
 * Device evidence (2026-09-26): the assistant model invented plausible URLs
 * for an information need ("current temperature in zagreb" → batacel.com /
 * bbmedia.com / bbcdn.com — none of them weather sites, none mentioned by
 * the user). Each fetch burned 6–25 s across the OkHttp fast path AND the
 * WebView fallback, and the error envelope the model received back was the
 * raw engine string ("Fetch failed: java.lang.IllegalStateException: the
 * page could not be loaded (net::ERR_ADDRESS_UNREACHABLE)") — nothing told
 * the model that the URL itself was dead or that a web search is the right
 * move instead.
 *
 * [describeFetchFailure] keeps the raw reason class in plain language (never
 * hides it) and adds what the model needs: which host failed, in which
 * class, and the instruction to search rather than retry the same URL.
 * Unrecognized messages pass through verbatim — a misclassified coach line
 * is worse than none.
 */

/**
 * True when [throwable]'s whole cause chain carries no HTTP response — the
 * host is dead at the DNS/TCP layer, so the hidden-WebView fallback (which
 * beats bot WALLS, not dead hosts) cannot do better and must be skipped.
 * Never true for bot-wall errors: an HTTP 403/429 means the connection
 * itself is fine and the WebView retry is the recovery path.
 */
fun isTransportDeadError(throwable: Throwable?): Boolean {
    var t: Throwable? = throwable
    while (t != null) {
        val msg = (t.message ?: "").lowercase()
        if (t is UnknownHostException ||
            t is ConnectException ||
            t is SocketTimeoutException ||
            "unable to resolve host" in msg ||
            "err_name_not_resolved" in msg ||
            "err_address_unreachable" in msg ||
            "err_internet_disconnected" in msg ||
            "failed to connect" in msg ||
            "econnrefused" in msg ||
            "econnreset" in msg
        ) {
            return true
        }
        t = if (t is ExecutionException) t.cause else t.cause
    }
    return false
}

/**
 * Translates a raw both-paths-failed message into the error envelope the
 * model reads. Known transport classes get the failing host, a plain-language
 * reason, and the "search instead, don't retry" coach; anything else is
 * returned unchanged.
 */
fun describeFetchFailure(rawMessage: String, url: String): String {
    val host = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }
    val target = if (host.isNullOrBlank()) "The site" else "'$host'"
    val msg = rawMessage.lowercase()
    val reason = when {
        "err_name_not_resolved" in msg || "unable to resolve host" in msg ->
            "$target does not exist (DNS lookup failed)"
        "err_address_unreachable" in msg ||
            "err_internet_disconnected" in msg ||
            "econnrefused" in msg ||
            "econnreset" in msg ||
            "failed to connect" in msg ->
            "$target exists but its server could not be reached from this " +
                "device's network"
        "timeout" in msg || "timed out" in msg ->
            "$target did not respond in time"
        else -> return rawMessage
    }
    return "The page could not be loaded: $reason. Do not retry this URL — " +
        "if the user needs this information, run a web search instead."
}
