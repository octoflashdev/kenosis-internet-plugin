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
 * Phase-1.5 fallback fetch engine: renders a page in a hidden native
 * [android.webkit.WebView] (the OS WebView IS the JS engine — the Edge
 * Gallery pattern) and extracts its post-JS text. Serves the JS-shell pages
 * (a `<div id="root"></div>` SPA) that the OkHttp+jsoup fast path sees as
 * ~empty text.
 *
 * Lifecycle: WebView must be created on the main thread (Android 16 / API 36
 * enforces this strictly — a HandlerThread worked intermittently on older
 * Android but throws `Calling View methods on another thread than the UI
 * thread` on newer releases). So the handler is the main-thread Looper. The
 * WebView is never added to a view hierarchy (headless); `destroy()`s it when
 * the extraction lands (or the watchdog fires, or [close] is called). One fetch
 * at a time; a second concurrent fetch fails fast instead of racing the
 * renderer.
 *
 * Budget: the caller (InternetPluginService) enforces the 15 s render cap via
 * `future.get(15 s)`; the internal watchdog below enforces the SAME cap from
 * the inside so a wedged renderer never leaks past `close()`. Fast path (10 s)
 * + WebView (15 s) = 25 s, under the host invoke timeout.
 *
 * Extraction: `document.body.innerText` (after stripping SCRIPT/STYLE/SVG/
 * IFRAME/TEMPLATE). innerText captures all visible text including data values
 * in divs/spans that a selector walk misses — critical for data-heavy SPAs
 * (windfinder, accuweather) that render numbers in non-semantic elements.
 *
 * Hygiene: JS on, blockNetworkImage(true) (bandwidth/faster text-ready),
 * LOAD_NO_CACHE, third-party cookies ON (a throwaway headless fetcher with no
 * user identity — SERP bot walls need their challenge cookies; see
 * [createWebView]), first-party cookies left default (logged-out JS pages
 * work). The REAL system-WebView UA with the "; wv)" token stripped
 * ([realWebviewUa]) — sites bot-block the wv token, and a stale hardcoded
 * Chrome version is itself a bot signal.
 */
/** WebView render result: the page [title], the post-JS plain [text]
 *  (innerText — RAG/chat grounds on this via the host's .txt), and the
 *  stripped-HTML-precursor [html] (the clone's innerHTML with
 *  SCRIPT/STYLE/SVG/IFRAME/TEMPLATE already removed; Kotlin runs this
 *  through the shared jsoup WEB_SAFELIST so both fetch paths share ONE
 *  allowlist). [html] is the reader sidecar source — display-only. */
data class WebViewContent(val title: String, val text: String, val html: String)

class WebViewPageFetcher(context: Context) {

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
        /**
         * Hard deadline (from load start) for the extracted text to become
         * non-blank; the empty-render poll loop stops here, before the 15 s
         * watchdog, so the fetch fails with the specific "no readable
         * content" reason instead of a generic render timeout.
         */
        const val RENDER_CONTENT_DEADLINE_MS = 12500L
        /** Interval between empty-render extraction retries. */
        private const val EMPTY_RENDER_POLL_MS = 2000L
        /** Fallback UA when the system WebView's own is unavailable (see
         *  [realWebviewUa]). Chrome-mobile, no "; wv)" token. */
        private const val WEBVIEW_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        /**
         * Pure, unit-testable: the REAL system-WebView user agent with the
         * "; wv)" token stripped (that token is how sites detect and bot-block
         * WebViews). Using the real UA matters beyond honesty: the previously
         * hardcoded Chrome/120 was two years stale by 2026-09 — an outdated
         * browser version is ITSELF a bot-protection signal, and Qwant's
         * DataDome hung its challenge on the stale UA in the +73 live test
         * (shell rendered script-only and never self-healed). The real UA
         * carries the device's actual Chrome version + Android version.
         * Null/blank input → [WEBVIEW_UA] fallback; so does a pathological
         * input whose stripped form lost its UA identity (no "Mozilla").
         */
        fun realWebviewUa(defaultUa: String?): String {
            val ua = defaultUa?.trim().orEmpty()
            if (ua.isEmpty()) return WEBVIEW_UA
            val stripped = ua.replace("; wv)", ")")
            return if (stripped.isBlank() || !stripped.contains("Mozilla")) {
                WEBVIEW_UA
            } else {
                stripped
            }
        }

        /**
         * Pure, unit-testable: should the extraction be retried? A BLANK
         * render before the deadline means the page hasn't produced readable
         * content yet — typically a bot-wall challenge shell (DataDome's
         * first paint is script-only; its challenge JS runs, sets the
         * clearance cookie, and reloads the page with the real payload, all
         * AFTER the first onPageFinished) — while non-blank text is content
         * and a passed deadline means it never will be.
         */
        fun shouldRetryExtraction(text: String, nowMs: Long, deadlineMs: Long): Boolean =
            text.isBlank() && nowMs < deadlineMs

        /**
         * Pure, unit-testable: given the page URL reported at onPageFinished
         * and any MAIN-FRAME error captured during the load, decide whether
         * the "finished" page is extractable content or the WebView's built-in
         * error screen. Returns the failure reason (fed to the fetch future as
         * an exception message → the tool's error envelope), or null when the
         * page is content.
         *
         * Why this exists — the WebView fires onPageFinished for its own ERROR
         * page too. +73 on-device regression: Google IFL picked a Facebook
         * post as the top result; Facebook redirected the render to a
         * `fb://native_post/…` deep link, the WebView showed "Web page not
         * available … net::ERR_UNKNOWN_URL_SCHEME", and the extractor served
         * that 212-char error text to the model as the search observation.
         * A redirect to a non-web scheme (fb://, intent://, twitter://…) or
         * any main-frame load error must FAIL the fetch, never extract.
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
         * Post-JS extraction: uses `document.body.innerText` as the PRIMARY
         * text source — this captures all visible text including data values
         * in `<div>`/`<span>` elements that a selector-based walk (h1-h6/p/li/
         * td/…) would miss. Many data-heavy SPAs (windfinder, accuweather) render
         * their numbers in divs/spans, not in semantic block elements; the old
         * selector walk captured page headings and labels but not the actual
         * data. innerText is the same thing Readability.js / reader-mode uses.
         *
         * Also returns `html`: the clone's `innerHTML` (SCRIPT/STYLE/SVG/IFRAME/
         * TEMPLATE already removed by the clone strip) — the precursor to the
         * reader sidecar. Kotlin runs it through the jsoup WEB_SAFELIST so only
         * `<b>/<br>/<p>/headings/lists` survive (no `<img>/<a>/<script>`/attrs).
         * Capped at 500k here (MAX_TEXT_CHARS) to bound the evaluateJavascript
         * return; the reader caps it again as defense-in-depth. The JS does the
         * COARSE script/style strip; jsoup does the FINE tag allowlist.
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
         * SERP anchor walk (+73): collect ABSOLUTE http(s) anchor hrefs in
         * document order, deduped, capped — joined with newlines in the SAME
         * {title, text} envelope the text walk uses, so the whole
         * render/poll/complete pipeline is shared verbatim: an empty link
         * list is blank text and is polled/retried until the content
         * deadline exactly like a blank article render. Used by
         * [fetchLinks] (web_search's google SERP fallback).
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
  var links = [];
  var anchors = document.querySelectorAll('a[href]');
  for (var i = 0; i < anchors.length; i++) {
    var href = anchors[i].href;
    if (!/^https?:\/\//i.test(href)) continue;
    if (seen[href]) continue;
    seen[href] = 1;
    links.push(href);
    if (links.length >= 40) break;
  }
  return {title: (document.title || '').trim(), text: links.join('\n')};
})()"""

        /**
         * Raw-HTML walk (+75): return the page's full `outerHTML` (capped) in
         * the SAME {title, text} envelope — `text` CARRIES the HTML. Used by
         * [fetchHtml] for the duckduckgo SERP retry, where the resolver needs
         * the anchor markup, not text or hrefs alone. A challenge/bot-wall
         * page's outerHTML is never blank (head + script tags count), so the
         * blank-render retry loop completes on the first extraction — right
         * for this walk: challenge HTML goes back to the caller, whose
         * resolver + captcha detection take over; a real SERP HTML parses
         * into results directly.
         *
         * ESCAPING: Kotlin RAW string — single backslashes for JS escapes
         * (see [LINK_WALK_JS] for the +73 doubled-backslash regression).
         */
        private const val HTML_WALK_JS = """(function () {
  var html = document.documentElement ? document.documentElement.outerHTML : '';
  if (html.length > 500000) { html = html.substring(0, 500000); }
  return {title: (document.title || '').trim(), text: html};
})()"""

    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var currentWebView: WebView? = null

    /**
     * Fetch `url` in a hidden WebView and extract its post-JS text + the
     * stripped-HTML precursor ([WebViewContent]). Returns a Future the caller
     * bounds with [RENDER_TIMEOUT_S] (the internal watchdog enforces the same
     * cap and destroys the renderer either way).
     */
    fun fetch(url: String): Future<WebViewContent> =
        startFetch(url, DOM_WALK_JS) { json ->
            WebViewContent(
                title = json.optString("title", ""),
                text = json.optString("text", ""),
                html = json.optString("html", ""),
            )
        }

    /**
     * Fetch `url` in a hidden WebView and collect its ANCHOR hrefs (the SERP
     * anchor walk — see [LINK_WALK_JS]): the future resolves to
     * (title, newline-joined links); the caller splits and filters. Same
     * pipeline, caps, and failure semantics as [fetch].
     */
    fun fetchLinks(url: String): Future<Pair<String, String>> =
        startFetch(url, LINK_WALK_JS) { json ->
            json.optString("title", "") to json.optString("text", "")
        }

    /**
     * Fetch `url` in a hidden WebView and return its RAW HTML
     * ([HTML_WALK_JS] — `documentElement.outerHTML`, capped). The SERP retry
     * for HTML engines (duckduckgo): the DOM/text walks destroy the anchor
     * MARKUP the resolvers parse ([InternetPluginService.resolveDdgResultUrls]
     * needs `a.result__a` elements, innerText is plain text), so the retry
     * hands the post-JS DOM back verbatim. A challenge shell returns its
     * challenge HTML — the caller's resolver then yields nothing and the
     * captcha path takes over; a SOLVED challenge (clearance cookie from the
     * visible solve-it WebView, shared process-wide via CookieManager)
     * returns the real SERP and the resolver reads it. Same pipeline, caps,
     * and failure semantics as [fetch].
     */
    fun fetchHtml(url: String): Future<String> =
        startFetch(url, HTML_WALK_JS) { json ->
            json.optString("text", "")
        }

    private fun <R> startFetch(
        url: String,
        js: String,
        parser: (JSONObject) -> R,
    ): Future<R> {
        val future = CompletableFuture<R>()
        if (!busy.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("WebView fetcher busy"))
            return future
        }
        val finished = AtomicBoolean(false)
        handler.post { startLoad(url, future, finished, js, parser) }
        return future
    }

    /** Tear down everything (called from Service.onDestroy). Idempotent. */
    fun close() {
        handler.post {
            currentWebView?.let {
                Log.d(TAG, "WebViewPageFetcher: destroying pending renderer (close)")
                destroyWebView(it)
            }
            currentWebView = null
        }
    }

    // ------------------------------------------------------------------ //
    // Pipeline — everything below runs on the HandlerThread               //
    // ------------------------------------------------------------------ //

    @SuppressLint("SetJavaScriptEnabled")
    private fun <R> startLoad(
        url: String,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        js: String,
        parser: (JSONObject) -> R,
    ) {
        // Deadline for the extracted text to become non-blank (see
        // [shouldRetryExtraction]) — measured from load start, before the
        // 15 s watchdog, so a blank render fails with the specific reason.
        val contentDeadline = System.currentTimeMillis() + RENDER_CONTENT_DEADLINE_MS
        // Internal watchdog: same 15 s cap the caller enforces via future.get —
        // this side guarantees the renderer is destroyed even if the caller
        // already gave up and walked away from the future.
        handler.postDelayed({
            if (finished.compareAndSet(false, true)) {
                busy.set(false)
                future.completeExceptionally(
                    TimeoutException("WebView render exceeded ${RENDER_TIMEOUT_S}s")
                )
                Log.w(TAG, "FC: [InternetPlugin] webview render timeout (${RENDER_TIMEOUT_S}s): $url")
                currentWebView?.let { destroyWebView(it) }
                currentWebView = null
            }
        }, TimeUnit.SECONDS.toMillis(RENDER_TIMEOUT_S))

        val webView = try {
            createWebView()
        } catch (e: Exception) {
            Log.w(TAG, "FC: [InternetPlugin] webview create failed: $e")
            if (finished.compareAndSet(false, true)) {
                busy.set(false)
                future.completeExceptionally(e)
            }
            return
        }
        currentWebView = webView

        webView.webViewClient = object : WebViewClient() {
            private var pageFinished = false
            /** First main-frame error of the CURRENT navigation; reset per
             *  onPageStarted so a redirect hop that failed then recovered
             *  (a second navigation) is not poisoned by the first hop. */
            private var mainFrameError: String? = null

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameError = null
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
                    completeFetch(view, future, finished) {
                        it.completeExceptionally(IllegalStateException(failure))
                    }
                    return
                }
                waitReadyThenExtract(view, contentDeadline, future, finished, js, parser)
            }
        }
        webView.loadUrl(url)
    }

    private fun createWebView(): WebView {
        val webView = WebView(appContext)
        webView.settings.apply {
            javaScriptEnabled = true
            blockNetworkImage = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            // The REAL system-WebView UA with the "; wv)" detection token
            // stripped — see [realWebviewUa] for why the real version
            // matters (the stale hardcoded Chrome/120 was itself a bot
            // signal).
            userAgentString = realWebviewUa(
                android.webkit.WebSettings.getDefaultUserAgent(appContext),
            )
            // First-party acceptance is the process-wide CookieManager flag
            // (CaptchaCookies): DENIED except while a human check is pending.
            // Third-party follows the gate below. No DOM storage changes —
            // leave platform defaults.
        }
        // Third-party cookies only while a human check is pending: the SERP
        // bot walls (Qwant's DataDome, +73 live test) only complete their
        // challenge flow when the challenge domains (e.g.
        // *.captcha-delivery.com) can set their cookies. Outside that flow
        // this is a throwaway headless fetcher with no user identity or
        // session — fetch-and-forget (user directive, +75): no cookies at
        // all, first- or third-party.
        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, CaptchaGate.pending != null)
        return webView
    }

    /** Wait [AJAX_SETTLE_DELAY_MS] after onPageFinished for AJAX/XHR data to
     *  populate the DOM, then extract. readyState is already "complete" by the
     *  time onPageFinished fires, so polling it is useless — the data we need
     *  arrives via post-load XHR, not initial resource loading. */
    private fun <R> waitReadyThenExtract(
        webView: WebView,
        contentDeadline: Long,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        js: String,
        parser: (JSONObject) -> R,
    ) {
        if (finished.get()) return
        Log.d(TAG, "FC: [InternetPlugin] webview waiting ${AJAX_SETTLE_DELAY_MS}ms for AJAX settle")
        handler.postDelayed({
            if (finished.get()) return@postDelayed
            Log.d(TAG, "FC: [InternetPlugin] webview AJAX settle elapsed — extracting")
            extractWithRetry(webView, contentDeadline, future, finished, js, parser)
        }, AJAX_SETTLE_DELAY_MS)
    }

    /**
     * Extract the rendered text, retrying while the page renders BLANK.
     *
     * A blank render before the deadline is usually a bot-wall challenge
     * shell — DataDome's first paint (Qwant SERP, +73 live test) is
     * script-only with zero visible text; its challenge JS then runs, sets
     * the clearance cookie and RELOADS the page with the real payload, all
     * after our first onPageFinished — or a very slow SPA still hydrating.
     * Every attempt reads the LIVE DOM, so a reload that lands between
     * attempts is captured by the next one. Only non-blank text completes
     * the fetch; blank past the deadline fails with the honest reason
     * instead of completing with an empty observation.
     */
    private fun <R> extractWithRetry(
        webView: WebView,
        contentDeadline: Long,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        js: String,
        parser: (JSONObject) -> R,
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
                completeFetch(webView, future, finished) { it.completeExceptionally(e) }
                return@evaluateJavascript
            }
            if (shouldRetryExtraction(text, System.currentTimeMillis(), contentDeadline)) {
                Log.d(TAG, "FC: [InternetPlugin] webview render blank — retrying in ${EMPTY_RENDER_POLL_MS}ms")
                handler.postDelayed({
                    extractWithRetry(webView, contentDeadline, future, finished, js, parser)
                }, EMPTY_RENDER_POLL_MS)
                return@evaluateJavascript
            }
            if (text.isBlank()) {
                val title = json.optString("title", "")
                Log.w(TAG, "FC: [InternetPlugin] webview rendered no readable content (title=${title.take(60)})")
                completeFetch(webView, future, finished) {
                    it.completeExceptionally(
                        IllegalStateException("the page rendered no readable content"),
                    )
                }
                return@evaluateJavascript
            }
            Log.d(TAG, "FC: [InternetPlugin] webview extracted ${text.length} chars, title=${json.optString("title", "").take(80)}")
            Log.d(TAG, "FC: [InternetPlugin] webview text preview: ${text.take(200)}")
            completeFetch(webView, future, finished) { it.complete(parsed) }
        }
    }

    /** One-shot terminal completion: exactly one caller wins the CAS and
     *  completes the future; the renderer is torn down either way (it is no
     *  longer needed once the fetch is decided). */
    private fun <R> completeFetch(
        webView: WebView,
        future: CompletableFuture<R>,
        finished: AtomicBoolean,
        completion: (CompletableFuture<R>) -> Unit,
    ) {
        val won = finished.compareAndSet(false, true)
        destroyWebView(webView)
        currentWebView = null
        if (won) {
            busy.set(false)
            completion(future)
        }
    }

    /** WebView.destroy() must run on the thread that created it. */
    private fun destroyWebView(webView: WebView) {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "FC: [InternetPlugin] webview destroy failed: $e")
        }
    }
}