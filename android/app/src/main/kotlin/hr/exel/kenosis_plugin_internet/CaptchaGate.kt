package hr.exel.kenosis_plugin_internet

import android.webkit.CookieManager

/**
 * Process-shared "human check needed" state.
 *
 * When a search engine answers a [InternetPluginService] SERP request with a
 * bot-wall challenge instead of results (Qwant's DataDome, DuckDuckGo's
 * anomaly page), the challenge used to render in the HIDDEN WebView
 * ([WebViewPageFetcher] never attaches to a view hierarchy) — invisible,
 * unsolvable, and the fetch just died. The gate records that a check is
 * pending so the plugin app can surface it:
 *  - MainActivity's Flutter UI polls it (warning banner + a visible
 *    solve-it WebView) via the `kenosis_plugin/ui` method channel;
 *  - [CaptchaNotifications] posts the system notification that brings the
 *    user here.
 *
 * Solving works across renderers because `android.webkit.CookieManager` is
 * process-wide (and persisted): the challenge cookies the user earns in the
 * VISIBLE WebView are exactly the cookies the next hidden fetch sends —
 * Qwant's fast path stays fingerprint-blocked (DataDome flags the OkHttp TLS
 * stack), but the hidden-WebView SERP retry ([InternetPluginService.fetchSerpBody])
 * recovers on the earned clearance cookie.
 *
 * One pending check at a time (a new engine's set replaces the old one) —
 * the user solves one thing; the next blocked search re-arms the gate.
 * Service and activity share a process, so a @Volatile field is the whole
 * transport — no binder, no IPC.
 */
object CaptchaGate {

    /**
     * How long a pending check stays actionable. Challenge clearance cookies
     * expire on the engine's schedule we can't observe; after this window a
     * stale banner would point at a check that may no longer exist, so
     * [current] stops surfacing it (the next blocked search re-arms a fresh
     * gate anyway).
     */
    const val STALE_TTL_MS = 30L * 60 * 1000

    /** One pending human check: [engine] ("duckduckgo" | "qwant"), the
     *  [url] to load in the visible WebView (the SITE ORIGIN of the blocked
     *  URL via [solveUrlForHuman] — the clearance cookie is scoped to that
     *  domain, which the origin preserves), a raw [reason] for the
     *  log/details UI, and [sinceMs] for staleness + dedupe. */
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
 * not the blocked URL itself. The gate used to record the exact blocked URL —
 * for Qwant that is a JSON API endpoint (api.qwant.com/v3/search?...), and
 * the visible solve WebView rendered a raw JSON blob no human can act on
 * (device feedback, +75). The challenge UI lives on the site's own pages,
 * and the bot-wall clearance cookie is scoped to the domain — which the
 * origin preserves — so solving there earns exactly the cookies the exact
 * URL would. Unparseable or non-http(s) URLs come back unchanged (never
 * crash the arming path; the exact URL still renders, just less friendly).
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

/**
 * The plugin app's cookie stance: DENIED by default — this is a
 * fetch-and-forget tool, not a web browser (user directive, +75). The ONLY
 * legitimate cookie consumer is the human-check flow: `android.webkit`'s
 * CookieManager is process-wide, and the clearance cookies the user earns in
 * the visible solve WebView are exactly what the hidden WebView retry sends
 * (the whole recovery path — see [CaptchaGate]). So acceptance flips ON
 * while a check is pending and OFF the moment it resolves.
 *
 * The cookie JAR is deliberately never wiped: earned clearance stays on disk
 * but DORMANT — plain fetches never carry it (the OkHttp client installs no
 * CookieJar, so it is stateless), and WebViews only load while the gate is
 * armed, when acceptance is ON anyway. Wiping would only cost a re-solve.
 */
object CaptchaCookies {
    fun setAllowed(allowed: Boolean) {
        CookieManager.getInstance().setAcceptCookie(allowed)
    }
}
