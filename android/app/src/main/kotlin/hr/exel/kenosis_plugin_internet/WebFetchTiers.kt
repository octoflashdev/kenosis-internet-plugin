package hr.exel.kenosis_plugin_internet

import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * The 3-tier web-fetch ladder (plan: anonymous-first-tiered-web-fetch).
 *
 * Invariant (one sentence): fetch each URL with the least-powerful honest
 * client that yields readable text; escalate by exactly one tier on an
 * HONEST failure; never bypass, spoof or auto-click anything; a wall the
 * machine cannot pass becomes the user's choice in their own visible tab.
 *
 *  - Tier 1 — anonymous HTTP: plain [OkHttpClient] GET with the truthful
 *    product UA ("KenosisAI-web/1.0 (Android)" — NOT a browser UA; no
 *    spoofing, ever), text via [HtmlTextExtractor]. No site lists, no cookie
 *    replay, no bypass of anything.
 *  - Tier 2 — automatic browser: the URL renders in a VISIBLE tab through
 *    [WebViewPageFetcher] (the caller opens it) — visible to the user, no
 *    user action required to START. Stock UA, JS on, captcha probe unchanged.
 *  - Tier 3 — user interaction needed: the tool returns the failure AS THE
 *    ANSWER with the actionable tail ([needsInteractionMessage]); the
 *    re-trigger is the user's NEXT message, never an auto-retry.
 *
 * ENGINE URLs NEVER SEE TIER 1 with ONE carve-out: [skipTier1] routes engine
 * URLs to Tier 2 — an engine CONTENT surface (SERP, accounts, nav, CDN) is
 * the SERP scrape the ToS rule bans when OkHttp-fetched anonymously (since
 * 2026-10-07 Google serves organic results as encrypted goto-token URLs, so
 * many web_search candidate URLs ARE engine URLs). OUTBOUND RESULT
 * REDIRECTORS (Google `/goto?url=CAES-…`, `/url?…`, DDG `/l/?`, Qwant
 * `/redirect?…`) are the EXPLICIT RELAXATION (plan:
 * verbose-web-search-bubble fast path, user-approved 2026-10-12): a
 * redirector is a pointer to a THIRD-PARTY content page — OkHttp follows its
 * 302 with the truthful product UA and reads that content page, the same
 * legal read a link-resolver app does. Engine CONTENT hosts stay WebView-only
 * (the SERP-scrape ban is absolute; [TabManager.isEngineUrl] still routes
 * those away). The tier client holds NO engine knowledge of its own: all
 * engine-host + redirector facts live in [TabManager] (the test/sanity
 * guard pins that).
 *
 * Pure surface (JVM-testable — no OkHttp/WebView on unit-test paths):
 * [skipTier1], [decideAfterTier1], [decideAfterTier2], [needsInteractionMessage]
 * and the constants. [fetchAnonymous] is the only side-effectful member.
 */
object WebFetchTiers {

    /** The tier router's logcat tag (PID-filterable, same tag family). */
    private const val TAG = "InternetPlugin"

    /**
     * Hard cap on the whole Tier-1 call (connect + resolve + read). A
     * timed-out T1 is an honest failure → the predicate routes to Tier 2.
     * Budget math (pinned in WebFetchTiersTest): the fan-out's wait-all cap
     * is [T1_TIMEOUT_MS] + [T1_FANOUT_WAIT_MARGIN_MS] and runs BEFORE the
     * search round gate, so the render tail is pinned as
     * SEARCH_ROUND_BUDGET_MS + RENDER_TIMEOUT_S*1000 ≤ 44 s < the host's
     * 45 s invoke timeout.
     *
     * 5_000 (fast path, plan: verbose-web-search-bubble): the fan-out now
     * covers ≤3 candidates in PARALLEL (all-candidates fan-out) so wall
     * clock ≈ one T1 call, not three; 8 s paid that serial-era slack for
     * nothing. 2 s connect + 3 s read stays inside the 5 s callTimeout.
     */
    const val T1_TIMEOUT_MS = 5_000L

    /**
     * Slack on top of [T1_TIMEOUT_MS] for the fan-out's wait-all (multi-
     * gather): the pool has ≤3 workers so the slowest call still finishes
     * inside its own [T1_TIMEOUT_MS]; the margin only covers scheduling.
     * Past the cap the pool is [Executors.shutdownNow]'d — stragglers become
     * null (honest failure → tier 2) without stretching the round.
     *
     * 1_000 (was 2_000, fast path): ≤3 workers cover 3 candidates in
     * parallel; a smaller margin is enough scheduling slack. Part of the
     * controllable fast-path ceiling pin (≤ 7.1 s in WebFetchTiersTest):
     * SERP_SETTLE_MS + SERP_LINK_POLL_MS + T1_TIMEOUT_MS + this.
     */
    const val T1_FANOUT_WAIT_MARGIN_MS = 1_000L

    /**
     * Smallest extract the ladder serves directly. NOT per-site knowledge —
     * one constant: big enough to catch JS shells and CMP gate stubs (which
     * land far smaller), small enough that genuinely thin short pages still
     * escalate to the tab (where the renderer sees the same page the user
     * does) rather than a T1-only thin read.
     */
    const val MIN_READABLE_CHARS = 600

    /** The truthful product UA. NEVER a browser UA (no spoofing, ever). */
    const val USER_AGENT = "KenosisAI-web/1.0 (Android)"

    /** One anonymous-GET result: HTTP status, raw HTML, post-redirect URL. */
    data class Tier1Response(val status: Int, val rawHtml: String, val finalUrl: String)

    /** The escalation predicate's verdict on an outcome shape. */
    sealed interface TierDecision {
        /** Content is readable at this tier — serve it. */
        object Ready : TierDecision
        /** Honest failure / not enough text — escalate to the VISIBLE tab. */
        object EscalateToTab : TierDecision
        /** Tier 2 also failed honestly — user interaction needed (Tier 3). */
        object NeedsHuman : TierDecision
    }

    /**
     * Pure, unit-testable: should this URL skip Tier 1? Delegates entirely to
     * the shared [TabManager] predicates — the tier client MUST hold no
     * engine knowledge of its own (the test/sanity guard pins this still
     * holds). Engine CONTENT URLs skip; engine OUTBOUND REDIRECTORS
     * ([TabManager.isEngineRedirector]) do NOT — they are Tier-1-eligible
     * outbound pointers (explicit carve-out, plan: verbose-web-search-bubble
     * fast path; the 302 target's content page is what OkHttp actually
     * reads, and a captcha/interstitial target is just a candidate failure
     * routed to the tab ladder as before).
     */
    fun skipTier1(url: String): Boolean =
        TabManager.isEngineUrl(url) && !TabManager.isEngineRedirector(url)

    /**
     * Pure, unit-testable: the escalation predicate on the TIER-1 outcome
     * shape (plan table). 2xx + thick + no wall markers → Ready; ANY non-2xx,
     * any thin extract, and any 200-thick page carrying challenge markers →
     * EscalateToTab (some walls answer 200 with >600 chars of interstitial —
     * never serve a challenge page as content; Tier 2 re-detects and arms
     * CaptchaGate exactly as today).
     */
    fun decideAfterTier1(status: Int, text: String, rawHtml: String): TierDecision {
        if (status !in 200..299) return TierDecision.EscalateToTab
        if (text.length < MIN_READABLE_CHARS) return TierDecision.EscalateToTab
        if (InternetPluginService.looksLikeCaptchaPage(text)) {
            return TierDecision.EscalateToTab
        }
        if (InternetPluginService.looksLikeCaptchaPage(rawHtml)) {
            return TierDecision.EscalateToTab
        }
        return TierDecision.Ready
    }

    /**
     * Pure, unit-testable: the escalation predicate on the TIER-2 outcome.
     * A thick extract → Ready. A thin one with no captcha is an honest
     * "still not readable" → NeedsHuman (Tier 3). A captcha page in the tab
     * is ALSO Tier 3 — via the existing CaptchaGate flow, which the caller
     * (InternetPluginService) detects and arms BEFORE this predicate; this
     * function only sees the no-captcha remainder.
     */
    fun decideAfterTier2(text: String): TierDecision =
        if (text.length >= MIN_READABLE_CHARS) TierDecision.Ready else TierDecision.NeedsHuman

    /**
     * The Tier-3 error-envelope text (the failure IS the answer — the host
     * model relays it verbatim). Carries the SAME actionable tail that
     * [InternetPluginService.humanCheckMessage] established: open the tab,
     * complete the action, then ask again. NO auto-retry exists: the
     * re-trigger is the user's next message, and the next tool round
     * succeeds via Tier 1/2 because the user's action made the state
     * legitimate (the +75 captcha flow proves the pattern). The message text
     * is pinned verbatim in WebFetchTiersTest.
     */
    fun needsInteractionMessage(finalUrl: String): String =
        "The tool could not read this page — it needs interaction (a login " +
            "wall, a consent gate, or a script-only app). The tab is open at " +
            "$finalUrl. Open the Internet Search plugin app, complete the " +
            "action in the tab, then ask again."

    /**
     * The ONE OkHttp pool for every tier-1 fetch — single and lazy. The
     * multi-gather fan-out reuses it instead of building a client per call,
     * so concurrent fetches share a connection pool/threads (bounded risk on
     * low-end devices: ≤3 workers, each call capped at [T1_TIMEOUT_MS]).
     * Created here and NOWHERE else — the test/sanity guard pins
     * `OkHttpClient` to this file only.
     */
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(T1_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Tier 1 itself: anonymous OkHttp GET, product UA, [T1_TIMEOUT_MS] cap.
     * Follows redirects (the final URL is what a reader app would land on —
     * that is what the fetch log records). Runs on the binder thread (no
     * WebView, no main-thread hop). All failures are the CALLER's honest
     * escalation: it catches and routes to Tier 2.
     */
    fun fetchAnonymous(url: String): Tier1Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        sharedClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val status = response.code
            val finalUrl = response.request.url.toString()
            Log.i(
                TAG,
                "FC: [InternetPlugin] tier=1 okhttp $status ${body.length} chars " +
                    "(host=${runCatching { java.net.URI(url).host }.getOrNull() ?: "?"})",
            )
            return Tier1Response(status, body, finalUrl)
        }
    }

    /**
     * Tier-1 fan-out for web_search multi-gather: fetch every [url]
     * CONCURRENTLY (fixed pool, ≤3 workers) with the same honest client and
     * cap as [fetchAnonymous] — wall clock ≈ one T1 call, not N. Each
     * worker wraps [fetchAnonymous] in runCatching, so ANY transport failure
     * maps that URL to null. The returned map is COMPLETE over [urls]: a
     * key is present even when its fetch threw or the wait-all deadline
     * ([T1_TIMEOUT_MS] + [T1_FANOUT_WAIT_MARGIN_MS]) passed — the caller
     * routes every null to the tier-2 ladder (pinned in WebFetchTiersTest).
     * The caller owns engine knowledge: this takes bare URLs and must never
     * see a search-engine URL (the sanity guard pins both directions).
     *
     * Optional progress hooks (display step feed, plan: verbose-web-search-
     * bubble) — called INSIDE the workers so the timestamps are the real
     * per-request completion times, not the wait-all drain:
     *  - [onWorkerStarted](index, url) just before the worker's own fetch,
     *  - [onWorkerResult](index, url, outcome, elapsedMs) with that worker's
     *    measured wall time (a null [outcome] is an honest failure — thrown
     *    or past-deadline).
     * Hooks receive bare values ONLY (no host/label parsing here — the
     * CALLER's lambda extracts identity if it wants it, keeping the tier
     * client free of engine-URL text processing). Hook failures are hidden:
     * a progress push must never break a fetch.
     */
    fun fetchAnonymousParallel(
        urls: List<String>,
        onWorkerStarted: ((index: Int, url: String) -> Unit)? = null,
        onWorkerResult: ((index: Int, url: String, outcome: Tier1Response?, elapsedMs: Long) -> Unit)? = null,
    ): Map<String, Tier1Response?> {
        if (urls.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(urls.size, 3))
        try {
            val started = System.currentTimeMillis()
            val futures = urls.mapIndexed { i, url ->
                pool.submit(
                    Callable {
                        try {
                            onWorkerStarted?.invoke(i, url)
                        } catch (_: Throwable) {}
                        val workerStart = System.currentTimeMillis()
                        val outcome = runCatching { fetchAnonymous(url) }.getOrNull()
                        val elapsed = System.currentTimeMillis() - workerStart
                        try {
                            onWorkerResult?.invoke(i, url, outcome, elapsed)
                        } catch (_: Throwable) {}
                        url to outcome
                    },
                )
            }
            val deadline = started + T1_TIMEOUT_MS + T1_FANOUT_WAIT_MARGIN_MS
            val out = LinkedHashMap<String, Tier1Response?>()
            for ((url, future) in urls.zip(futures)) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                val pair = runCatching { future.get(remaining, TimeUnit.MILLISECONDS) }.getOrNull()
                out[url] = pair?.second
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }
}