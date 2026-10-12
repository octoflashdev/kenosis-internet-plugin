package hr.exel.kenosis_plugin_internet

/**
 * Process-shared "human check needed" state.
 *
 * When a search engine answers a [InternetPluginService] SERP request with a
 * bot-wall challenge instead of results (Qwant's DataDome, DuckDuckGo's
 * anomaly page), the visible tab shows the challenge. The gate records that a
 * check is pending so the plugin app can surface it:
 *  - MainActivity's Flutter UI polls it (warning banner + a visible
 *    solve-it tab) via the `kenosis_plugin/ui` method channel;
 *  - [CaptchaNotifications] posts the system notification that brings the
 *    user here.
 *
 * The human solves the check in that visible tab. `captchaSolved` is only a
 * dismiss ping — there is NO cookie-unlock auto-retry and NO re-arm of
 * scraping cookies: after the solve the model searches again on its own.
 *
 * One pending check at a time (a new engine's set replaces the old one) —
 * the user solves one thing; the next blocked search re-arms the gate.
 * Service and activity share a process, so a @Volatile field is the whole
 * transport — no binder, no IPC.
 */
object CaptchaGate {

    /**
     * How long a pending check stays actionable. After this window a stale
     * banner would point at a check that may no longer exist, so [current]
     * stops surfacing it (the next blocked search re-arms a fresh gate).
     */
    const val STALE_TTL_MS = 30L * 60 * 1000

    /** One pending human check: [engine], the [url] to load in the visible
     *  tab (the SITE ORIGIN of the blocked URL via [solveUrlForHuman]), a raw
     *  [reason] for the log/details UI, and [sinceMs] for staleness + dedupe. */
    data class Pending(
        val engine: String,
        val url: String,
        val reason: String,
        val sinceMs: Long,
    )

    @Volatile
    var pending: Pending? = null
        private set

    /** Records/refreshes a pending check. Idempotent per engine; a different
     *  engine replaces the pending one (solve one thing at a time). */
    fun set(engine: String, url: String, reason: String, nowMs: Long) {
        pending = Pending(engine, url, reason, nowMs)
    }

    /** Clears the gate — but ONLY when it still names [engine]: a stale
     *  clear() from an old solve must not dismiss a newer engine's check. */
    fun clear(engine: String) {
        val current = pending ?: return
        if (current.engine == engine) pending = null
    }

    /** Pure, unit-testable: is [p] past its actionable window? */
    fun isStale(p: Pending?, nowMs: Long, ttlMs: Long = STALE_TTL_MS): Boolean =
        p != null && nowMs - p.sinceMs > ttlMs

    /** The gate as the UI should see it: a pending check that is not stale.
     *  A stale gate is dropped here so the banner/notification state self-
     *  cleans without a dedicated sweeper. */
    fun current(nowMs: Long): Pending? {
        val p = pending ?: return null
        if (isStale(p, nowMs)) {
            pending = null
            return null
        }
        return p
    }
}

/**
 * The URL the human is asked to solve: the SITE ORIGIN of the blocked URL,
 * not the blocked URL itself (the challenge UI lives on the site's own pages).
 * Unparseable or non-http(s) URLs come back unchanged (never crash the arming
 * path; the exact URL still renders, just less friendly).
 *
 * java.net.URI (not android.net.Uri) so [CaptchaGateTest] stays a plain JVM
 * unit test.
 */
fun solveUrlForHuman(rawUrl: String): String = try {
    val uri = java.net.URI(rawUrl)
    val scheme = uri.scheme?.lowercase()
    if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
        "$scheme://${uri.host}/"
    } else {
        rawUrl
    }
} catch (_: Exception) {
    rawUrl
}
