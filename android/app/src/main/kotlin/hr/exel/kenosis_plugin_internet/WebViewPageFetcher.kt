package hr.exel.kenosis_plugin_internet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Tier 2 of the web-fetch ladder: the VISIBLE-tab extract engine
 * (plan: anonymous-first-tiered-web-fetch). [WebViewPageFetcher.fetch] renders
 * a URL into a tab's [WebView] and extracts its post-JS DOM — the escalation
 * target of Tier 1 ([WebFetchTiers]) and the only route for engine URLs.
 *
 * The model reads only what a visible tab displays: this class loads a URL
 * into a tab's [WebView] (created by [createTabWebView], owned by
 * [TabManager] / the tab UI) and extracts its post-JS DOM. There is no hidden
 * renderer, and the tab WebView is NEVER destroyed here — the user can
 * inspect it at any time.
 *
 * Lifecycle: WebView must be created on the main thread (Android 16 / API 36
 * enforces this strictly). The handler is the main-thread Looper; callers on a
 * binder thread use [createTabWebView] / [onMain] hops. One extraction at a
 * time; a second concurrent extract fails fast instead of racing the renderer.
 *
 * Budget: the caller (InternetPluginService) bounds the wait via
 * `future.get(...)`; the internal watchdog enforces [RENDER_TIMEOUT_S] from
 * the inside so a wedged renderer never leaks past [close].
 *
 * Extraction: `document.body.innerText` (after stripping SCRIPT/STYLE/SVG/
 * IFRAME/TEMPLATE). innerText captures all visible text including data values
 * in divs/spans that a selector walk misses — critical for data-heavy SPAs.
 *
 * Hygiene: JS + image load follow [BrowserSettings] (images default ON —
 * a real browser loads them; JS default ON — SPAs and captcha need it),
 * domStorage ON, LOAD_NO_CACHE. **Stock UA** — the WebView's own default,
 * identity marker included. Identity is NOT stripped or spoofed: this client
 * is a visible browser the user can inspect, and a doctored UA is both a
 * ToS problem and (a stale hardcoded Chrome) its own bot signal.
 */
/** WebView render result: the page [title], the post-JS plain [text]
 *  (innerText — RAG/chat grounds on this via the host's .txt), and the
 *  script-stripped [html] (the clone's innerHTML with SCRIPT/STYLE/SVG/
 *  IFRAME/TEMPLATE removed — display-only sidecar). */
data class WebViewContent(val title: String, val text: String, val html: String)

class WebViewPageFetcher(
    context: Context,
    /**
     * Display-step feed hook (plan: verbose-web-search-bubble) — one call
     * per render-phase event, `{"code","params"}` shape the service converts
     * into a progress push. The service wires its `progress()` helper here at
     * the lazy init; tests pass the inert default. Display-only facts (see
     * the payload contract on InternetPluginService.progress): counts /
     * statuses / the failure-reason CLASSES — never page text, a URL with
     * embedded query content, or a raw redirect target. Fires on the MAIN
     * thread (all pipeline seams run there) and is fire-and-forget — a hook
     * failure must never break a render (each emission is best-effort).
     */
    private val onProgress: (code: String, params: JSONObject) -> Unit = { _, _ -> },
) {

    companion object {
        private const val TAG = "InternetPlugin"
        /** Hard render cap (same value the service enforces via future.get). */
        const val RENDER_TIMEOUT_S = 15L
        /**
         * Fixed delay after onPageFinished before extracting. Many data-heavy
         * SPAs (windfinder, accuweather) load their actual data via AJAX/XHR
         * AFTER the page's static resources are ready — `document.readyState`
         * is already "complete" when onPageFinished fires, so polling it is
         * useless. A hard 5 s delay gives the AJAX round-trip time to populate
         * the DOM. Capped by the 15 s watchdog.
         */
        private const val AJAX_SETTLE_DELAY_MS = 5000L
        /** Interval between empty-render extraction retries. */
        private const val EMPTY_RENDER_POLL_MS = 2000L

        /**
         * SERP LINK-WALK settle (fast path, plan: verbose-web-search-bubble):
         * the anchor walk only collects RESULT hrefs — udm=14 organic anchors
         * are SERVER-RENDERED, in the initial HTML the way the text walk's
         * data tables are not, so the 5 s AJAX wait buys nothing for them.
         * The blank-guard poll (see [shouldRetryExtraction] in link mode)
         * still covers a slow/challenged SERP. Content walks keep the full
         * 5 s. Part of the controllable fast-path ceiling pinned in
         * WebFetchTiersTest: SERP_SETTLE_MS + SERP_LINK_POLL_MS +
         * WebFetchTiers.T1_TIMEOUT_MS + T1_FANOUT_WAIT_MARGIN_MS ≤ 7100.
         */
        const val SERP_SETTLE_MS = 600L

        /** SERP link-walk BLANK re-poll cadence — faster than the content
         *  walk's 2 s text poll because a blank SERP is (a) chrome-hydration
         *  (organic anchors land seconds later) or (b) a challenge shell, and
         *  0.5 s detects either without stretching the 12.5 s deadline. */
        const val SERP_LINK_POLL_MS = 500L

        /**
         * Hard deadline (from load start) for the extracted text to become
         * non-blank; the empty-render poll loop stops here, before the 15 s
         * watchdog, so the fetch fails with the specific "no readable
         * content" reason instead of a generic render timeout. UNCHANGED for
         * BOTH walk modes (the link walk re-polls into the same deadline).
         */
        const val RENDER_CONTENT_DEADLINE_MS = 12500L

        /** The exact failure text for a render that never produced readable
         *  content — thrown AND matched on by the tier router ([WebFetchTiers]
         *  routes it to Tier 3 for browser_fetch; the web_search walk keeps
         *  its own try-next-candidate semantics). Public so the string match
         *  in InternetPluginService is exact, never a copy. */
        const val TAB_EMPTY_MSG = "the page rendered no readable content"

        /**
         * Pure, unit-testable: should the extraction be retried?
         *
         * A BLANK render before the deadline means the page hasn't produced
         * readable content yet — typically a bot-wall challenge shell
         * (DataDome's first paint is script-only; its challenge JS runs, sets
         * the clearance cookie, and reloads the page with the real payload,
         * all AFTER the first onPageFinished).
         *
         * STABILITY gate (2026-10-03 on-device, yr.no daily-table): the first
         * non-blank extract can be PARTIAL — chrome renders first, the data
         * table hydrates from a later XHR — so a first extract (previousText
         * null) polls once to confirm it settled, and a CHANGED extract vs
         * the previous poll keeps polling (the DOM is still filling). Two
         * consecutive identical extracts = settled = no retry. A passed
         * deadline never retries: blank fails honestly; non-blank-unstable
         * completes with the latest text at the caller.
         *
         * LINK WALKS ([firstNonEmptyWins] — fast path, the SERP only): the
         * first NON-BLANK walk WINS outright — anchors don't hydrate like
         * data tables (the udm=14 anchors are server-rendered), so the
         * stability confirm would just pay a fixed 0.5 s poll for nothing.
         * A BLANK link walk still re-polls (chrome-first SERPs, challenge
         * shells) to the unchanged deadline, same as content.
         */
        fun shouldRetryExtraction(
            text: String,
            nowMs: Long,
            deadlineMs: Long,
            previousText: String? = null,
            firstNonEmptyWins: Boolean = false,
        ): Boolean {
            if (nowMs >= deadlineMs) return false
            if (text.isBlank()) return true
            if (firstNonEmptyWins && previousText == null) {
                // First non-empty link walk — the SERP's results are readable
                // as-is; skipping the stability confirm is the fast path's
                // win, and hydration re-polling of CONTENT (link walk never
                // has a previousText confirmed table) does not apply.
                return false
            }
            if (previousText == null) return true
            return text != previousText
        }

        /**
         * Pure, unit-testable: given the page URL reported at onPageFinished
         * and any MAIN-FRAME error captured during the load, decide whether
         * the "finished" page is extractable content or the WebView's built-in
         * error screen. Returns the failure reason (fed to the fetch future as
         * an exception message → the tool's error envelope), or null when the
         * page is content.
         *
         * Why this exists — the WebView fires onPageFinished for its own ERROR
         * page too. +73 on-device regression: a top result was a Facebook
         * post; Facebook redirected the render to a `fb://native_post/…` deep
         * link, the WebView showed "Web page not available …
         * net::ERR_UNKNOWN_URL_SCHEME", and the extractor served that 212-char
         * error text to the model as the search observation. A redirect to a
         * non-web scheme (fb://, intent://, twitter://…) or any main-frame
         * load error must FAIL the fetch, never extract.
         */
        fun loadFailureReason(pageUrl: String?, mainFrameError: String?): String? {
            if (mainFrameError != null) {
                return "the page could not be loaded ($mainFrameError)"
            }
            if (pageUrl == null) return "the page could not be loaded"
            val lower = pageUrl.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return "the page redirected to a non-web link ($pageUrl) " +
                    "that the in-app browser cannot read"
            }
            return null
        }

        /**
         * Pure, unit-testable: is this EXTRACTED page actually the WebView's
         * built-in error screen that slipped past [loadFailureReason]?
         *
         * Why this exists — 2026-10-03 on-device: a share-URL analyse saved a
         * library document titled "Web page not available" (717-byte .txt) —
         * the WebView's own error page got extracted and returned as a
         * successful fetch despite the [loadFailureReason] guard being
         * present in that build (the error page rendered on a navigation
         * where no main-frame [onReceivedError]/[onReceivedHttpError] was
         * captured). Content-level backstop: the error page has a
         * deterministic title (both spellings — this device's WebView uses
         * the LEGACY "Web page not available", current Chromium says
         * "Webpage not available") and a short body naming the `net::ERR_…`
         * code. Real content never carries either signature; failing here
         * turns a garbage document into an honest fetch error the host
         * already relays ("could not access the page").
         *
         * The net::ERR_ text check is scoped to SHORT extracts (< 600 chars —
         * error pages are tiny) so an article QUOTING a net:: code early is
         * never refused.
         */
        fun errorPageReason(title: String, text: String): String? {
            val t = title.lowercase().trim()
            if (t == "webpage not available" || t == "web page not available") {
                return "the page could not be loaded (the browser served its " +
                    "error page)"
            }
            val head = text.trim().take(200).lowercase()
            if (head.contains("net::err_") && text.trim().length < 600) {
                return "the page could not be loaded (browser error page " +
                    "naming a net:: error)"
            }
            return null
        }

        /**
         * Post-JS extraction: uses `document.body.innerText` as the PRIMARY
         * text source — this captures all visible text including data values
         * in `<div>`/`<span>` elements that a selector-based walk (h1-h6/p/li/
         * td/…) would miss. Many data-heavy SPAs (windfinder, accuweather) render
         * their numbers in divs/spans, not in semantic block elements; the old
         * selector walk captured page headings and labels but not the actual
         * data. innerText is the same thing Readability.js / reader-mode uses.
         *
         * Also returns `html`: the clone's `innerHTML` (SCRIPT/STYLE/SVG/IFRAME/
         * TEMPLATE already removed by the clone strip) — the display-only
         * sidecar. Capped at 500k here (MAX_TEXT_CHARS) to bound the
         * evaluateJavascript return.
         *
         * Returns the OBJECT (not a JSON string) so evaluateJavascript delivers
         * it as a plain `{"title":..,"text":..,"html":..}` document to Kotlin.
         */
        private const val DOM_WALK_JS = """(function () {
  var SKIP = ['SCRIPT','STYLE','NOSCRIPT','SVG','IFRAME','TEMPLATE'];
  function extract(el) {
    if (!el) return {text: '', html: ''};
    // Clone, strip non-content elements, then read innerText + innerHTML.
    var clone = el.cloneNode(true);
    clone.querySelectorAll(SKIP.join(',')).forEach(function (s) {
      s.remove();
    });
    // Prefer innerText (computed, respects visibility) over textContent.
    var t = (clone.innerText || clone.textContent || '').replace(/\s+/g, ' ').trim();
    var h = clone.innerHTML || '';
    if (h.length > 500000) { h = h.substring(0, 500000); }
    return {text: t, html: h};
  }
  var text = '';
  var html = '';
  if (document.body) {
    var r = extract(document.body);
    text = r.text;
    html = r.html;
  }
  // Truncate text to the same budget the selector walk used.
  if (text.length > 24576) { text = text.substring(0, 24576); }
  return {title: (document.title || '').trim(), text: text, html: html};
})()"""

        /**
         * SERP anchor walk: collect ABSOLUTE http(s) anchor hrefs, deduped,
         * RESULT-LIKE FIRST — joined with newlines in the SAME {title, text}
         * envelope the text walk uses, so the whole render/poll/complete
         * pipeline is shared verbatim: an empty link list is blank text and is
         * polled/retried until the content deadline exactly like a blank
         * article render. Used by [fetchLinks] (web_search's SERP walk on the
         * visible SERP tab).
         *
         * ORDERING (2026-10-07 on-device, twice: `40/45 links → 0 usable`):
         * mobile Google leads with ~40 engine-family chrome hrefs (accounts,
         * support, policies, www.google nav) BEFORE the organic results. A
         * naive "off-site" test treats `accounts.google.com` as a result
         * (different hostname from `www.google.com`) and the results bucket
         * fills with chrome — real links never get collected. Result-shaped
         * here means: an engine redirector (`/url?`, `/goto?`, `/l/?`,
         * `/redirect?`) or an off-site host that is NOT in the engine family
         * (google.*, doubleclick, duckduckgo, qwant, gstatic,
         * googleusercontent, googleapis, ggpht). Engine-family leftovers ride
         * in `other`.
         *
         * GOTO TOKENS (2026-10-07 CDP probe of the live SERP): Google now
         * serves organic results as `a.UBFage[href="/goto?url=CAES-…"]` —
         * the target is an ENCRYPTED blob (not a plaintext URL in `href`,
         * `ping`, `data-*` or `jsdata`; `data-href` count was 0). It cannot
         * be unwrapped client-side; the Lucky tab NAVIGATES the goto URL and
         * Google 302s to the real page. The camera/Lens button also rides
         * `/goto?url=CAES-…` (`a.v9gBvd`) with an EMPTY text label — so a
         * redirector href is result-shaped ONLY when the anchor has non-empty
         * visible text (the result card: title + cite + snippet). Empty-text
         * redirector anchors are chrome and are skipped entirely (not even
         * `other` — Kotlin's [InternetPluginService.isEngineRedirector]
         * exemption would otherwise let them through as candidates).
         *
         * ESCAPING: this is a Kotlin RAW string — backslashes pass through
         * VERBATIM, so JS escapes need a SINGLE backslash (`\/`, `'\n'`), not
         * the doubled `\\/` / `'\\n'` an ordinary Kotlin string would use.
         * The +73 first cut shipped `\\/` and `'\\n'`: the JS regex literal
         * `/^https?:\\/\\//i` treats `\\` as an escaped backslash, the next
         * bare `/` TERMINATES the regex mid-pattern → the whole script is a
         * syntax error → evaluateJavascript returns null → every google SERP
         * walk failed with "WebView JS extraction returned null" (live
         * 15:04 round; the path had never been exercised before because the
         * IFL redirect usually yields a usable target).
         */
        private const val LINK_WALK_JS = """(function () {
  var seen = {};
  var results = [];
  var other = [];
  function isEngineFamily(host) {
    return /(^|\.)(google\.[a-z.]+|doubleclick\.net|duckduckgo\.com|qwant\.com|gstatic\.com|googleusercontent\.com|googleapis\.com|ggpht\.com)$/.test(host);
  }
  var anchors = document.querySelectorAll('a[href]');
  for (var i = 0; i < anchors.length; i++) {
    var href = anchors[i].href;
    if (!/^https?:\/\//i.test(href)) continue;
    if (seen[href]) continue;
    seen[href] = 1;
    // Engine redirectors carry the real target (plaintext) or an encrypted
    // goto token. Result-shaped ONLY with visible card text — empty-text
    // redirector anchors are chrome (the camera/Lens `/goto?url=CAES-…`).
    if (/\/url\?|\/goto\?|\/l\/\?|\/redirect\?/.test(href)) {
      var label = (anchors[i].innerText || '').trim();
      if (label && results.length < 40) results.push(href);
      continue;
    }
    var organic = false;
    try {
      var host = new URL(href).hostname;
      organic = !isEngineFamily(host);
    } catch (e) {
      organic = false;
    }
    if (organic) {
      if (results.length < 40) results.push(href);
    } else if (other.length < 40) {
      other.push(href);
    }
  }
  var links = results.concat(other);
  return {title: (document.title || '').trim(), text: links.join('\n')};
})()"""

        /**
         * Raw-HTML walk: DELETED (dead code — no production caller). The
         * captcha probe uses [extractLoaded] / [DOM_WALK_JS] text+html, and
         * the SERP walk uses [LINK_WALK_JS].
         */
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val pending = java.util.Collections.synchronizedList(
        mutableListOf<CompletableFuture<*>>(),
    )

    /**
     * Create a VISIBLE tab WebView (stock UA + [BrowserSettings]). MUST run on
     * the main thread — binder callers hop via [onMain].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createTabWebView(): WebView {
        val webView = WebView(appContext)
        webView.settings.apply {
            javaScriptEnabled = BrowserSettings.javaScript
            blockNetworkImage = !BrowserSettings.loadImages
            domStorageEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            // STOCK UA — do not touch userAgentString. The WebView default
            // is the honest identity of a WebView-based browser; stripping
            // or spoofing it misrepresents the client.
        }
        // A real tab needs cookies (login walls, captcha clearance, site
        // continuity). Wipe-on-close ([BrowserSettings.wipeOnClose]) owns
        // the other end — this is a browser, not a fetch-and-forget client.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // Title reporting for the tab strip (URL rides the extract path's
        // WebViewClient — the tab surface must never replace that one).
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                TabHolder.updateChromeFor(view, title, view.url)
            }
        }
        return webView
    }

    /**
     * Run [block] on the main thread and return its result (bounded).
     *
     * Every WebView call must land here when the caller is a binder thread —
     * `WebView.getUrl()` is NOT safe off-main and throws after a successful
     * extract (2026-10-07: AccuWeather returned 4890 chars / 19°C and the
     * result was discarded by a binder-thread `url` read). Catch is
     * [Throwable] on purpose: WebView's thread check historically throws a
     * bare `java.lang.Throwable`, and an `Exception`-only catch would leave
     * this future incomplete until the 5 s timeout.
     */
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        handler.post {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    /**
     * Load `url` in the TAB's WebView and extract its post-JS text + the
     * script-stripped HTML ([WebViewContent]). The tab WebView stays alive —
     * the user can inspect it. Returns a Future the caller bounds with
     * [RENDER_TIMEOUT_S].
     */
    fun fetch(webView: WebView, url: String): Future<WebViewContent> =
        startFetch(webView, url, DOM_WALK_JS, CONTENT_WALK) { json ->
            WebViewContent(
                title = json.optString("title", ""),
                text = json.optString("text", ""),
                html = json.optString("html", ""),
            )
        }

    /**
     * Load `url` in the TAB's WebView and collect its ANCHOR hrefs (the SERP
     * anchor walk — see [LINK_WALK_JS]): the future resolves to
     * (title, newline-joined links); the caller splits and filters. Same
     * pipeline, caps, and failure semantics as [fetch], on the SERP WALK
     * mode (fast path: 0.6 s settle, first non-empty walk wins, 0.5 s blank
     * re-poll — see [SERP_LINK_WALK]).
     */
    fun fetchLinks(webView: WebView, url: String): Future<Pair<String, String>> =
        startFetch(webView, url, LINK_WALK_JS, SERP_LINK_WALK) { json ->
            json.optString("title", "") to json.optString("text", "")
        }

    /**
     * Extract from the tab's ALREADY-LOADED DOM (no navigation) — e.g. a
     * captcha probe of the SERP tab after [fetchLinks] already settled it.
     */
    fun extractLoaded(webView: WebView): Future<WebViewContent> =
        startExtract(webView, contentDeadlineMs(), DOM_WALK_JS, CONTENT_WALK, { json ->
            WebViewContent(
                title = json.optString("title", ""),
                text = json.optString("text", ""),
                html = json.optString("html", ""),
            )
        }, finished = AtomicBoolean(false))

    /**
     * Re-walk the ALREADY-LOADED DOM's anchors (no navigation). The first
     * [fetchLinks] can settle on chrome-only hrefs while the organic results
     * are still hydrating; this picks them up from the live page — on the
     * SERP WALK mode (first non-empty wins here too: the re-walk only happens
     * after a candidate-less first walk, so a page that now HAS anchors must
     * not wait for a second confirm).
     */
    fun extractLinksLoaded(webView: WebView): Future<Pair<String, String>> =
        startExtract(webView, contentDeadlineMs(), LINK_WALK_JS, SERP_LINK_WALK, { json ->
            json.optString("title", "") to json.optString("text", "")
        }, finished = AtomicBoolean(false))

    /** Tear down pending extractions (service onDestroy). Tab WebViews are
     *  owned by [TabManager] and are NOT destroyed here. Idempotent. */
    fun close() {
        handler.post {
            val doomed = synchronized(pending) { pending.toList() }
            pending.clear()
            for (f in doomed) {
                f.completeExceptionally(
                    IllegalStateException("WebViewPageFetcher closed"),
                )
            }
            busy.set(false)
        }
    }

    // ------------------------------------------------------------------ //
    // Pipeline — everything below runs on the main thread                 //
    // ------------------------------------------------------------------ //

    /**
     * One EXTRACTION WALK's pacing (fast path, plan: verbose-web-search-
     * bubble). The whole pipeline shares one shape — load → settle →
     * extract → poll — so a walk mode is the three knobs that differ:
     *  - CONTENT: the 5 s AJAX settle + 2 s stability/blank poll ([fetch],
     *    the Lucky tab, the captcha probe) — untouched.
     *  - SERP_LINK: 0.6 s settle + 0.5 s blank poll + first-non-empty-wins
     *    ([fetchLinks], [extractLinksLoaded] — anchor hrefs, fast path).
     */
    private data class WalkMode(
        val settleMs: Long,
        val pollMs: Long,
        val firstNonEmptyWins: Boolean,
    )

    private val CONTENT_WALK = WalkMode(AJAX_SETTLE_DELAY_MS, EMPTY_RENDER_POLL_MS, false)
    private val SERP_LINK_WALK = WalkMode(SERP_SETTLE_MS, SERP_LINK_POLL_MS, true)

    private fun contentDeadlineMs(): Long =
        System.currentTimeMillis() + RENDER_CONTENT_DEADLINE_MS

    private fun <R> startFetch(
        webView: WebView,
        url: String,
        js: String,
        mode: WalkMode,
        parser: (JSONObject) -> R,
    ): Future<R> {
        val future = CompletableFuture<R>()
        if (!busy.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("WebView fetcher busy"))
            return future
        }
        synchronized(pending) { pending.add(future) }
        val finished = AtomicBoolean(false)
        handler.post { startLoad(webView, url, future, finished, js, parser, mode) }
        return future
    }

    private fun <R> startExtract(
        webView: WebView,
        contentDeadline: Long,
        js: String,
        mode: WalkMode,
        parser: (JSONObject) -> R,
        finished: AtomicBoolean,
    ): Future<R> {
        val future = CompletableFuture<R>()
        if (!busy.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("WebView fetcher busy"))
            return future
        }
        synchronized(pending) { pending.add(future) }
        handler.post {
            extractWithRetry(webView, contentDeadline, future, finished, js, parser, mode)
        }
        return future
    }

    private fun <R> startLoad(
        webView: WebView,
        url: String,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        js: String,
        parser: (JSONObject) -> R,
        mode: WalkMode,
    ) {
        // Deadline for the extracted text to become non-blank (see
        // [shouldRetryExtraction]) — measured from load start, before the
        // 15 s watchdog, so a blank render fails with the specific reason.
        val contentDeadline = contentDeadlineMs()
        // Internal watchdog: same 15 s cap the caller enforces via future.get —
        // this side guarantees the future settles even if the caller already
        // gave up. The TAB WebView is left alone (still visible).
        handler.postDelayed({
            Log.w(TAG, "FC: [InternetPlugin] webview render timeout (${RENDER_TIMEOUT_S}s): $url")
            onProgress("render_timeout", JSONObject())
            completeFetch(finished, future) {
                it.completeExceptionally(
                    TimeoutException("WebView render exceeded ${RENDER_TIMEOUT_S}s"),
                )
            }
        }, TimeUnit.SECONDS.toMillis(RENDER_TIMEOUT_S))

        // THE WebViewClient for this tab — the tab surface must NOT replace it
        // (2026-10-08: TabPlatformView's chrome client stole it mid-fetch after
        // presentUi launched the UI; onPageFinished then never reached the
        // extract pipeline → 15 s render timeout on a SERP the user could already
        // see, and the Lucky tab never got a URL). Chrome reporting for every
        // later navigation (user clicks) lives HERE too, so the client stays
        // installed after the fetch completes.
        webView.webViewClient = object : WebViewClient() {
            private var pageFinished = false
            /** First main-frame error of the CURRENT navigation; reset per
             *  onPageStarted so a redirect hop that failed then recovered
             *  (a second navigation) is not poisoned by the first hop. */
            private var mainFrameError: String? = null

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameError = null
                TabHolder.updateChromeFor(view, view.title, url)
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                TabHolder.updateChromeFor(view, view.title, url ?: view.url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    mainFrameError = error.description?.toString() ?: "network error"
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Main resource only — sub-frame/XHR failures never kill the
                // page. Keep the FIRST error (an ERR_ before an HTTP code is
                // the more fundamental one); error pages (Cloudflare blocks,
                // soft 404s) must fail the fetch, not serve their text.
                if (request.isForMainFrame && mainFrameError == null) {
                    mainFrameError = "HTTP ${errorResponse.statusCode}"
                }
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                // Chrome first — user clicks after this fetch still need live
                // title/URL, and the first-finish gate below must not skip them.
                TabHolder.updateChromeFor(view, view.title, pageUrl ?: view.url)
                // onPageFinished can fire more than once (history/redirects);
                // start the AJAX-settle wait only for the first main-frame finish.
                if (pageFinished || finished.get()) return
                pageFinished = true
                Log.d(TAG, "FC: [InternetPlugin] webview onPageFinished: $pageUrl")
                // The WebView fires this for its built-in ERROR page too —
                // extracting that page's text serves "Web page not available"
                // garbage as the observation. Fail instead (see
                // [loadFailureReason] for the on-device case this guards).
                val failure = loadFailureReason(pageUrl, mainFrameError)
                if (failure != null) {
                    Log.w(TAG, "FC: [InternetPlugin] webview load failed: $failure")
                    // Progress feed (display-only): the failure-REASON CLASS —
                    // loadFailureReason carries error descriptions / deep-link
                    // shapes, never query params. Truncate defensively.
                    onProgress(
                        "load_failed",
                        JSONObject().put("reason", failure.take(120)),
                    )
                    completeFetch(finished, future) {
                        it.completeExceptionally(IllegalStateException(failure))
                    }
                    return
                }
                // Progress feed: the first main-frame finish of THIS load.
                onProgress("page_loaded", JSONObject())
                waitReadyThenExtract(view, contentDeadline, future, finished, js, parser, mode)
            }
        }
        webView.loadUrl(url)
    }

    /** Wait [WalkMode.settleMs] after onPageFinished for AJAX/XHR data to
     *  populate the DOM, then extract. readyState is already "complete" by the
     *  time onPageFinished fires, so polling it is useless — the data we need
     *  arrives via post-load XHR, not initial resource loading. (SERP link
     *  walks settle a shorter 600 ms — see [WalkMode].) */
    private fun <R> waitReadyThenExtract(
        webView: WebView,
        contentDeadline: Long,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        js: String,
        parser: (JSONObject) -> R,
        mode: WalkMode,
    ) {
        if (finished.get()) return
        Log.d(TAG, "FC: [InternetPlugin] webview waiting ${mode.settleMs}ms for AJAX settle")
        onProgress("settle_wait", JSONObject().put("ms", mode.settleMs))
        handler.postDelayed({
            if (finished.get()) return@postDelayed
            Log.d(TAG, "FC: [InternetPlugin] webview AJAX settle elapsed — extracting")
            onProgress("settle_done", JSONObject())
            extractWithRetry(webView, contentDeadline, future, finished, js, parser, mode)
        }, mode.settleMs)
    }

    /**
     * Extract the rendered text, retrying while the page renders BLANK or is
     * STILL HYDRATING.
     *
     * A blank render before the deadline is usually a bot-wall challenge
     * shell — DataDome's first paint is script-only with zero visible text;
     * its challenge JS then runs, sets the clearance cookie and RELOADS the
     * page with the real payload, all after our first onPageFinished — or a
     * very slow SPA still hydrating. Every attempt reads the LIVE DOM, so a
     * reload that lands between attempts is captured by the next one.
     *
     * Progressive hydration (2026-10-03 on-device, yr.no daily-table): the
     * chrome + current-conditions render first, the data table arrives via a
     * later XHR — the first extract is NON-blank but tableless, and the old
     * gate completed instantly (the chat answer then invented 0/0/0 "daily
     * temperatures"). A CHANGED extract vs the previous poll means the DOM
     * is still filling — keep polling; two consecutive identical extracts
     * mean settled — complete. The deadline caps the loop, and a non-blank
     * but still-unstable page at the deadline completes with the LATEST text
     * (partial content beats failing a page that rendered something real);
     * only a blank page past the deadline fails with the honest reason.
     */
    private fun <R> extractWithRetry(
        webView: WebView,
        contentDeadline: Long,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        js: String,
        parser: (JSONObject) -> R,
        mode: WalkMode,
        previousText: String? = null,
    ) {
        if (finished.get()) return
        webView.evaluateJavascript(js) { result ->
            if (finished.get()) return@evaluateJavascript
            val parsed: R
            val text: String
            val json: JSONObject
            try {
                if (result.isNullOrBlank() || result == "null") {
                    throw IllegalStateException("WebView JS extraction returned null")
                }
                // evaluateJavascript JSON-encodes the returned object directly.
                json = JSONObject(result)
                parsed = parser(json)
                // The retry/blank gate keys on the text field both payloads
                // carry (WebViewContent.text / the SERP Pair.second).
                text = json.optString("text", "")
            } catch (e: Exception) {
                Log.w(TAG, "FC: [InternetPlugin] webview extraction failed: $e")
                completeFetch(finished, future) { it.completeExceptionally(e) }
                return@evaluateJavascript
            }
            if (shouldRetryExtraction(
                    text,
                    System.currentTimeMillis(),
                    contentDeadline,
                    previousText,
                    firstNonEmptyWins = mode.firstNonEmptyWins,
                )
            ) {
                val why =
                    if (text.isBlank()) "render blank" else "text still changing (hydration)"
                Log.d(
                    TAG,
                    "FC: [InternetPlugin] webview $why — retrying in ${mode.pollMs}ms",
                )
                // Progress feed: the poll/retry event, with the reason class
                // ("blank" | "hydration") and the NEXT poll's interval.
                onProgress(
                    "render_retry",
                    JSONObject()
                        .put("why", if (text.isBlank()) "blank" else "hydration")
                        .put("pollMs", mode.pollMs),
                )
                handler.postDelayed({
                    extractWithRetry(
                        webView,
                        contentDeadline,
                        future,
                        finished,
                        js,
                        parser,
                        mode,
                        previousText = text,
                    )
                }, mode.pollMs)
                return@evaluateJavascript
            }
            if (text.isBlank()) {
                val title = json.optString("title", "")
                Log.w(TAG, "FC: [InternetPlugin] webview rendered no readable content (title=${title.take(60)})")
                // Progress feed: the honest empty-render terminal (before the
                // caller maps it to TAB_EMPTY / try-next semantics).
                onProgress("render_blank", JSONObject())
                completeFetch(finished, future) {
                    it.completeExceptionally(
                        IllegalStateException(TAB_EMPTY_MSG),
                    )
                }
                return@evaluateJavascript
            }
            // Error-page content backstop (see [errorPageReason]): the
            // guard above only sees navigation events; an error page that
            // rendered without a captured main-frame error is refused HERE,
            // by its own title/body signature, never served as content.
            val errReason = errorPageReason(json.optString("title", ""), text)
            if (errReason != null) {
                Log.w(TAG, "FC: [InternetPlugin] webview error-page extract blocked: $errReason")
                completeFetch(finished, future) { it.completeExceptionally(IllegalStateException(errReason)) }
                return@evaluateJavascript
            }
            Log.d(TAG, "FC: [InternetPlugin] webview extracted ${text.length} chars, title=${json.optString("title", "").take(80)}")
            Log.d(TAG, "FC: [InternetPlugin] webview text preview: ${text.take(200)}")
            // Progress feed: the settled extract's SIZE only (display fact).
            onProgress("extracted", JSONObject().put("chars", text.length))
            completeFetch(finished, future) { it.complete(parsed) }
        }
    }

    /** One-shot terminal completion: exactly one caller wins the CAS and
     *  completes the future. The TAB WebView is left alive (visible). */
    private fun <R> completeFetch(
        finished: AtomicBoolean,
        future: CompletableFuture<R>,
        completion: (CompletableFuture<R>) -> Unit,
    ) {
        if (!finished.compareAndSet(false, true)) return
        synchronized(pending) { pending.remove(future) }
        busy.set(false)
        completion(future)
    }
}
