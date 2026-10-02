package hr.exel.kenosis_plugin_internet

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import hr.exel.kenosis.plugin.IKenosisPlugin
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections

/**
 * The Kenosis plugin service: bound by the offline Kenosis host over binder
 * (AIDL `IKenosisPlugin`, contract file copied into both apps). This app is
 * the ONLY component of the ecosystem holding INTERNET.
 *
 * Tools:
 *  - browser_fetch(url): FAST path = plain OkHttp GET + jsoup HTML→text
 *    extraction; if the extracted text is thin (< MIN_USEFUL_CHARS — a JS
 *    shell like `<div id="root"></div>` extracts to almost nothing) the URL
 *    is re-fetched through a hidden WebView (WebViewPageFetcher) and the
 *    longer result wins. Dispatch is INTERNAL to the plugin: the model never
 *    chooses "render vs fetch", the tool contract is unchanged. Truncated
 *    server-side to the observation budget before returning.
 *  - web_search(query): the HOST picks the engine (google / duckduckgo /
 *    qwant). Each engine resolves an ordered RESULT-candidate list; the
 *    fetch+extract pipeline runs on candidates in order until one renders
 *    readable content (a non-app-gated host that 404s / bot-blocks / renders
 *    blank is skipped for the next result — the IFL single-pick regression).
 *    Lets the on-device model look up information it doesn't have without the
 *    user supplying a URL. Phase 2 swaps in a future headless browser
 *    behind the same invoke() seam.
 */
class InternetPluginService : Service() {

    companion object {
        const val TAG = "InternetPlugin"
        const val PLUGIN_SERVICE_ACTION = "hr.exel.kenosis_ai.PLUGIN_SERVICE"
        /** Observation budget FLOOR: text returned by any tool is never cut
         *  below this — the pre-+73 fixed cap, kept as the default when no
         *  host budget arrives (old host ↔ new plugin, either direction). */
        const val OBSERVATION_BUDGET_CHARS = 6144
        /** Observation budget CEILING: the WebView JS extraction cap (see
         *  WebViewPageFetcher). Raised from 24576 → 500000 for the
         *  share-URL / web-search → save-as-document flow: the host now asks
         *  for the WHOLE page (host `_webPageFetchMaxChars`) so it can be
         *  saved verbatim to the Files library and chatted about via RAG,
         *  instead of the truncated-observation + (removed) digit-fidelity
         *  path. The old grounded-web-search path still sends a small
         *  maxChars, so this raise is a no-op for it (coerceIn floor/ceiling
         *  only clamps the upper bound). */
        const val MAX_TEXT_CHARS = 500000

        /** Tag allowlist for the stripped-HTML fragment emitted alongside the
         *  plain text (the reader sidecar). Keeps structural + formatting tags
         *  so a saved web page renders with <b>/<br>/<p>/headings/lists instead
         *  of a flat wall of escaped text; drops <img>/<script>/<style>/<a>/
         *  <div>/<span> and ALL attributes (no class/style/href — offline, no
         *  url_launcher, no remote images). `Safelist.none()` + addTags gives
         *  no attributes. Jsoup.clean preserves the TEXT inside dropped tags
         *  (div/span/a) and removes only the disallowed tags themselves, so no
         *  content is lost — only the markup noise. Applied to BOTH the fast
         *  (jsoup) path and the WebView path (whose JS does the coarse
         *  script/style strip then hands innerHTML here for the fine allowlist). */
        val WEB_SAFELIST: Safelist = Safelist.none()
            .addTags(
                "b", "i", "em", "strong", "br", "p",
                "h1", "h2", "h3", "h4", "h5", "h6",
                "ul", "ol", "li", "blockquote", "pre", "hr",
            )

        /** jsoup-clean a page's HTML to the [WEB_SAFELIST] fragment the reader
         *  sidecar uses. [bodyHtml] is the raw <body> inner HTML (fast path) or
         *  the WebView clone's innerHTML; [baseUrl] resolves relative links
         *  (irrelevant once <a> is stripped, but jsoup requires it). Returns "" */
        fun stripToWebFragment(bodyHtml: String, baseUrl: String): String =
            try {
                Jsoup.clean(bodyHtml, baseUrl, WEB_SAFELIST)
            } catch (e: Exception) {
                // A malformed huge DOM shouldn't kill the fetch — the plain
                // text is still returned; the reader just falls back to it.
                ""
            }

        /** +73 j: the host scales the per-round observation budget to its
         *  free context window and sends it as the `maxChars` tool arg.
         *  Missing/stale values (0, negative — e.g. an old host that never
         *  sends it) keep today's fixed 6 KB; an out-of-range value clamps
         *  to the floor/ceiling. */
        fun effectiveObservationBudget(maxChars: Int): Int {
            if (maxChars <= 0) return OBSERVATION_BUDGET_CHARS
            return maxChars.coerceIn(OBSERVATION_BUDGET_CHARS, MAX_TEXT_CHARS)
        }
        /**
         * Phase-1.5 FEATURE GATE (kill-switch): the hidden-WebView fallback
         * behind the fast path. Flip to false to ship static-HTTP-only again.
         * Unlike the host's kDebugMode gate this is a named const so the
         * feature is ON in the Play-internal release build (the plugin's
         * release build is not debuggable) but still a one-line disable.
         */
        const val WEBVIEW_FALLBACK_ENABLED = true
        /** Text shorter than this (trimmed) counts as a JS-shell page. */
        const val MIN_USEFUL_CHARS = 200

        /** Pure, unit-testable: does this extracted text look like a JS shell
         *  (thin enough that the hidden-WebView render path could do better)? */
        fun shouldFallback(extractedText: String): Boolean =
            extractedText.trim().length < MIN_USEFUL_CHARS

        /** Max result candidates a single web_search fetches. Each fetch is
         *  fast-path 10 s + WebView 15 s = 25 s, so three is a hard ceiling —
         *  the host invoke timeout backstops a run of hung fetches, and
         *  failed fetches usually fail fast (404 / ERR_UNKNOWN_URL_SCHEME /
         *  bot-block fire in seconds), so the cap is rarely exhausted in
         *  wall-clock terms. One readable result stops the walk. */
        const val MAX_SEARCH_CANDIDATES = 3

        /** Pure: should a web_search candidate's text be ACCEPTED (returned to
         *  the model) or SKIPPED to try the next result? Accept readable text
         *  always; accept thin text ONLY on the last candidate (a short real
         *  page beats an error envelope); skip thin text when more
         *  candidates remain. [isLast] makes the trade-off explicit: never
         *  discard the only thing the search returned. Public for the
         *  unit-test pin. */
        fun acceptSearchResult(text: String, isLast: Boolean): Boolean =
            !shouldFallback(text) || isLast

        /** DuckDuckGo no-JS HTML endpoint — static HTML, first `a.result__a` wins. */
        private const val DDG_HTML_URL = "https://html.duckduckgo.com/html/?q=%s"
        /** Qwant v3 web API — JSON; rate-limited (HTTP 429/403) on abuse.
         *  The API hard-rejects any count except 10 with HTTP 400
         *  ("count must be equal to 10" — verified 2026-09-15: count=5 → 400
         *  on every query, count=10 + the plugin's UA → 200 with results).
         *  Public for the EngineResolverTest contract pin. */
        const val QWANT_API_URL = "https://api.qwant.com/v3/search/web?q=%s&count=10&locale=en_US"

        // -- Per-engine result resolvers (+73: host picks the engine) --

        /**
         * Pure, unit-testable: parse a DuckDuckGo HTML SERP (html.duckduckgo
         * .com/html) and return the RESULT URLs in order, deduped (capped at
         * 10). Results are either direct absolute links or
         * `/l/?uddg=<url-encoded-target>&rut=…` redirects — the `uddg` param
         * is unwrapped when present. Multiple results let the caller SKIP a
         * top pick that can never render (app-gated social post — see
         * [isAppGatedUrl]); empty list when nothing parseable exists
         * (rate-limit page, empty result set).
         */
        fun resolveDdgResultUrls(serpHtml: String): List<String> {
            val doc = Jsoup.parse(serpHtml)
            val out = LinkedHashSet<String>()
            for (a in doc.select("a.result__a")) {
                var href = a.attr("href")
                if (href.isEmpty()) continue
                if (href.startsWith("//")) href = "https:$href"
                val uddgIdx = href.indexOf("uddg=")
                if (uddgIdx >= 0) {
                    val enc = href.substring(uddgIdx + "uddg=".length).substringBefore('&')
                    val decoded = try {
                        java.net.URLDecoder.decode(enc, "UTF-8")
                    } catch (e: Exception) {
                        null
                    }
                    if (decoded != null &&
                        (decoded.startsWith("http://") || decoded.startsWith("https://"))
                    ) {
                        out.add(decoded)
                        if (out.size >= 10) break
                        continue
                    }
                }
                if (href.startsWith("http://") || href.startsWith("https://")) {
                    out.add(href)
                    if (out.size >= 10) break
                }
            }
            return out.toList()
        }

        /**
         * Pure, unit-testable: parse a Qwant v3 API JSON body and return the
         * result URLs in order, deduped (capped at 10). The API nests results
         * under `data.result.items.mainline[*].items[*].url` (mixed
         * verticals — web results can share the array with news); every
         * http(s) `url` is collected so the caller can skip an app-gated top
         * pick. Empty list on missing fields, malformed JSON, or an empty
         * result set.
         *
         * Tolerates RENDERED bodies: when the SERP was served through the
         * hidden WebView (the bot-block fallback — see
         * [fetchSerpBody]), Chrome ≥120 renders application/json in its JSON
         * viewer, whose toolbar text ("Copy", "Pretty print"…) can end up in
         * innerText AROUND the JSON. A body that fails to parse outright is
         * re-tried on the carve from the first '{' to the last '}'.
         */
        fun resolveQwantResultUrls(apiJson: String): List<String> {
            return resolveQwant(parseQwantJson(apiJson))
        }

        /** Pure: [resolveQwantResultUrls] on an already-parsed API object. */
        private fun resolveQwant(root: JSONObject?): List<String> {
            if (root == null) return emptyList()
            val data = root.optJSONObject("data") ?: return emptyList()
            val items = data.optJSONObject("result")?.optJSONObject("items")
                ?: return emptyList()
            val mainline = items.optJSONArray("mainline") ?: return emptyList()
            val out = LinkedHashSet<String>()
            outer@ for (g in 0 until mainline.length()) {
                val groupItems = mainline.optJSONObject(g)?.optJSONArray("items") ?: continue
                for (i in 0 until groupItems.length()) {
                    val url = groupItems.optJSONObject(i)?.optString("url") ?: continue
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        out.add(url)
                        if (out.size >= 10) break@outer
                    }
                }
            }
            return out.toList()
        }

        /** Pure: parse API JSON, carving from the first '{' to the last '}'
         *  when the body carries renderer chrome around the JSON. Null when
         *  no object-shaped region exists. */
        private fun parseQwantJson(body: String): JSONObject? {
            try {
                return JSONObject(body)
            } catch (e: Exception) {
                val first = body.indexOf('{')
                val last = body.lastIndexOf('}')
                if (first < 0 || last <= first) return null
                return try {
                    JSONObject(body.substring(first, last + 1))
                } catch (e2: Exception) {
                    null
                }
            }
        }

        /** Normalizes the host-injected `engine` param (+73). Unknown, missing,
         *  or blank values fall back to "google" — today's behavior. */
        fun parseEngineName(raw: String?): String {
            val v = raw?.trim()?.lowercase().orEmpty()
            return when (v) {
                "duckduckgo" -> "duckduckgo"
                "qwant" -> "qwant"
                else -> "google"
            }
        }

        // -- Bot-wall / human-check detection (+75) -------------------------- //

        /**
         * Substrings (lowercased) that identify a bot-wall challenge page in
         * place of results. DataDome (Qwant) serves `captcha-delivery.com`
         * frames and a "Press & Hold" CTA; DuckDuckGo's anomaly wall carries
         * a `/captcha` form and its "bots use DuckDuckGo too" copy. Generic
         * human-verification phrasing covers engines that rotate vendors.
         * The probe scans only the first 100 KB (every real marker sits in
         * the head/first paint) so a huge body never pays a full lowercase
         * copy.
         */
        private val CAPTCHA_MARKERS = listOf(
            "captcha-delivery.com", // DataDome challenge frame (Qwant)
            "datadome",
            "press & hold", // DataDome challenge CTA
            "/captcha", // DuckDuckGo anomaly form action
            "unfortunately, bots use duckduckgo too", // DDG anomaly copy
            "verify you are a human",
            "are you a human",
            "human verification",
        )

        /**
         * Pure, unit-testable: does this SERP body look like a bot-wall
         * challenge rather than results? Applied when the resolver yields
         * ZERO results — the body came back HTTP-200-shaped but empty, which
         * is exactly what a challenge page (or a JSON-API blocked interstitial
         * rendered in the WebView) parses to. Distinguishes "no results for
         * this query" from "the engine wants a human check" — the +74 live
         * failure read as the former and gave the user nothing to do.
         */
        fun looksLikeCaptchaPage(body: String): Boolean {
            if (body.isEmpty()) return false
            val probe = body.take(100_000).lowercase()
            return CAPTCHA_MARKERS.any { it in probe }
        }

        /**
         * Pure, unit-testable: is this the exception [fetchRawBody] throws for
         * an HTTP status engines use for bot walls? Message-matched (not
         * typed) because that throw site is a plain IllegalStateException
         * carrying "HTTP <code>".
         */
        fun isBotWallHttpError(e: Exception?): Boolean =
            e?.message == "HTTP 403" || e?.message == "HTTP 429"

        /**
         * The error-envelope text for an engine actively showing a human
         * check. This string is what the HOST's model relays to the user
         * (run_plugin_tool_round wraps the envelope error verbatim), so it
         * carries the actionable instruction. The "human check" signature is
         * STABLE — the plugin UI's describeFetchError keys on it.
         */
        fun humanCheckMessage(engine: String): String =
            "$engine is showing a human check (captcha). Open the " +
                "Internet Search plugin app, complete the check, then " +
                "search again."

        /** Whole-string bare-domain pattern (+73 i): one or more labels each
         *  followed by a dot, a 2+-letter TLD, an optional :port, and an
         *  optional path/query/fragment — `example.com`, `www.site.org/path?q=1`,
         *  `site.com:8080/x`. Wholesome prose never matches (it contains
         *  spaces), a lone word never matches (no label-dot-TLD shape). */
        private val SCHEMELESS_URL = Regex(
            "^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(:\\d{1,5})?([/?#].*)?$",
            RegexOption.IGNORE_CASE,
        )

        /** +73 i: a schemeless domain like `example.com` must fetch directly
         *  (bypassing the search engine) instead of erroring. The host's
         *  detection prompt teaches the model to prepend `https://` itself,
         *  but a small on-device model sometimes emits the bare domain — this
         *  is the backstop. Prepends `https://` only when the WHOLE string is
         *  a domain; anything else (prose, garbage, already-schemed) returns
         *  unchanged, so the downstream scheme check still rejects non-URLs
         *  with today's error. */
        fun normalizeSchemelessUrl(raw: String): String {
            val v = raw.trim()
            if (v.startsWith("http://") || v.startsWith("https://")) return v
            return if (SCHEMELESS_URL.matches(v)) "https://$v" else v
        }
        private const val HTTP_TIMEOUT_S = 10L
        private const val WEBVIEW_TIMEOUT_S = WebViewPageFetcher.RENDER_TIMEOUT_S // 15 s
        private const val MAX_FETCH_BYTES = 2L * 1024 * 1024 // 2 MB HTML cap
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36 KenosisPlugin/1.0"

        /** Google "I'm Feeling Lucky" endpoint — 302-redirects to the top result. */
        private const val GOOGLE_IFL_URL = "https://www.google.com/search?q=%s&btnI=1"

        /** Google's regular SERP (+73 fix): rendered in the WebView when IFL's
         *  single pick is unusable (app-gated social post, missing redirect) so
         *  the anchor walk can pick the NEXT result — IFL yields exactly one
         *  URL with no retry list. */
        private const val GOOGLE_SERP_URL = "https://www.google.com/search?q=%s"

        /**
         * Hosts whose web pages redirect a headless browser into an app deep
         * link (fb://, intent://) — the page can NEVER render for the
         * extractor, so a candidate on these hosts is skipped BEFORE wasting
         * a fetch on it (+73 on-device case: Google IFL's top pick was a
         * facebook.com post; the WebView followed its fb://native_post
         * redirect and the load failed with ERR_UNKNOWN_URL_SCHEME).
         */
        private val APP_GATED_HOSTS = listOf(
            "facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com",
        )

        /** Pure: the lowercase host of [url], or null when unparseable. Uses
         *  java.net.URI (not android.net.Uri) so the pure helpers below run
         *  in the plain-JVM unit tests without the Android stub. */
        private fun hostOf(url: String): String? = try {
            java.net.URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }

        /** Pure: does [url]'s host sit under an app-gated domain (m.facebook.com,
         *  www.tiktok.com…)? Unparseable garbage → false (the scheme check
         *  rejects it downstream). Public for the unit-test pin. */
        fun isAppGatedUrl(url: String): Boolean {
            val host = hostOf(url) ?: return false
            return APP_GATED_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** Pure: is [url] a Google own-domain link (nav, sign-in, ads,
         *  pagination)? The SERP anchor walk must drop them to find actual
         *  RESULT links. Covers national TLDs (google.de) and ad networks. */
        private fun isGoogleHost(url: String): Boolean {
            val host = hostOf(url) ?: return false
            return host.contains("google") || host.endsWith(".doubleclick.net")
        }

        /** Pure: the ordered list of USABLE result URLs from a SERP anchor
         *  walk — drop google's own links and app-gated social hosts, keep
         *  order. https-only: real organic results are https; http stragglers
         *  are more likely tracker redirects. Empty when every candidate is
         *  filtered. The full list lets the caller fall back to the NEXT
         *  result when the first can't be fetched/rendered (a non-app-gated
         *  host that still 404s / bot-blocks / renders blank — the case IFL's
         *  single pick can't recover from). Public for the unit-test pin. */
        fun pickGoogleResults(urls: List<String>): List<String> =
            urls.filter {
                it.startsWith("https://") && !isGoogleHost(it) && !isAppGatedUrl(it)
            }

        /** Pure: [pickGoogleResults].firstOrNull() — the single-pick alias
         *  kept for the existing unit-test pin ([AppGateFilterTest]). */
        fun pickGoogleResult(urls: List<String>): String? =
            pickGoogleResults(urls).firstOrNull()

        /**
         * In-process URL log — every browser_fetch / web_search call appends a
         * record, and every search round records the SEARCH-ENGINE request
         * itself (the IFL/SERP/API URL carrying the encoded query) via
         * [logSearchRequest] — the log shows searches, not just the result
         * pages fetched after them. Read by the plugin app's UI (MainActivity
         * method channel) so users can see exactly what the plugin requested
         * (open-source transparency). Synchronized: written on binder threads,
         * read on the UI thread. Capped at 200 entries to bound memory.
         */
        data class FetchRecord(
            val timestamp: Long,
            val tool: String,
            val query: String?,
            val requestedUrl: String,
            val finalUrl: String?,
            val title: String,
            val chars: Int,
            val path: String,
            /** "ok" on success; "error:<message>" on failure (includes HTTP status). */
            val status: String,
        )

        private const val FETCH_LOG_CAP = 200
        private val _fetchLog = Collections.synchronizedList(mutableListOf<FetchRecord>())

        /** Disk backing for the URL log. The log used to be in-process only:
         *  Android tears the plugin process down between host rounds (the
         *  host's binder drops it once a turn finishes and the cached process
         *  gets killed), so opening the plugin app often landed in a FRESH
         *  process with an empty log — "Requested URLs (0)" right after a
         *  fetch the logcat had just shown (2026-09-27 regression report).
         *  Loaded once on service create, rewritten after every append
         *  (single-thread executor — never on the caller's thread). */
        @Volatile private var logFile: File? = null
        private val logWrite = Executors.newSingleThreadExecutor()

        /** Pure record→JSON (public for the unit-test pin). Nullables go out
         *  as [JSONObject.NULL] so `optString().ifEmpty { null }` reads them
         *  back as null — the round-trip is asserted in [FetchLogJsonTest]. */
        fun recordToJson(r: FetchRecord): JSONObject =
            JSONObject()
                .put("timestamp", r.timestamp)
                .put("tool", r.tool)
                .put("query", r.query ?: JSONObject.NULL)
                .put("requestedUrl", r.requestedUrl)
                .put("finalUrl", r.finalUrl ?: JSONObject.NULL)
                .put("title", r.title)
                .put("chars", r.chars)
                .put("path", r.path)
                .put("status", r.status)

        /** Pure JSON→record (public for the unit-test pin): null on a
         *  malformed row (missing/ill-typed required field) so ONE bad row
         *  in the file never wipes the whole hydrated log. */
        fun recordFromJson(o: JSONObject): FetchRecord? = runCatching {
            FetchRecord(
                timestamp = o.getLong("timestamp"),
                tool = o.getString("tool"),
                query = o.optString("query").ifEmpty { null },
                requestedUrl = o.getString("requestedUrl"),
                finalUrl = o.optString("finalUrl").ifEmpty { null },
                title = o.getString("title"),
                chars = o.getInt("chars"),
                path = o.getString("path"),
                status = o.getString("status"),
            )
        }.getOrNull()

        /** Pure encode of the log, oldest-first (public for the unit-test
         *  pin) — the shape [persistFetchLog] writes to disk. */
        fun encodeFetchLogOldestFirst(records: List<FetchRecord>): String {
            val arr = JSONArray()
            for (r in records) arr.put(recordToJson(r))
            return arr.toString()
        }

        /** Pure decode of the log file text (public for the unit-test pin).
         *  Malformed rows are skipped; the result is capped at [cap] exactly
         *  like live appends (keep the NEWEST, drop the oldest). */
        fun decodeFetchLog(text: String, cap: Int = FETCH_LOG_CAP): List<FetchRecord> {
            val out = mutableListOf<FetchRecord>()
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return out
            for (i in 0 until arr.length()) {
                val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
                recordFromJson(o)?.let { out.add(it) }
            }
            while (out.size > cap) out.removeAt(0)
            return out
        }

        /** [Context] arrives with the service — safe to call repeatedly. */
        fun initFetchLogPersistence(context: Context) {
            synchronized(_fetchLog) {
                if (logFile != null) return
                val f = File(context.filesDir, "fetch_log.json")
                logFile = f
                if (f.exists()) {
                    val loaded = decodeFetchLog(f.readText())
                    _fetchLog.clear()
                    _fetchLog.addAll(loaded)
                }
            }
        }

        /** Persists the CURRENT log (oldest-first) off the caller's thread.
         *  Call AFTER appending, inside or outside the [_fetchLog] lock — the
         *  executor serializes writers; the snapshot is taken under lock. */
        private fun persistFetchLog() {
            val f = logFile ?: return
            val encoded = synchronized(_fetchLog) {
                encodeFetchLogOldestFirst(_fetchLog.toList())
            }
            logWrite.execute { runCatching { f.writeText(encoded) } }
        }

        /** Clears the URL log (the UI's "Delete all") and persists the EMPTY
         *  array — disk and RAM agree in one step, so a process restart
         *  rehydrating from fetch_log.json cannot resurrect the cleared rows
         *  (initFetchLogPersistence loads exactly what was last written).
         *  Same writer discipline as [persistFetchLog]: snapshot under the
         *  [_fetchLog] lock, write off-thread on the single-thread executor.
         *  Returns the JSON persisted (public for the unit-test pin, like
         *  the codec above — in JVM tests logFile is null, so the returned
         *  encoded payload IS the by-construction disk content). */
        fun clearFetchLog(): String {
            val encoded = synchronized(_fetchLog) {
                _fetchLog.clear()
                encodeFetchLogOldestFirst(_fetchLog.toList())
            }
            val f = logFile ?: return encoded
            logWrite.execute { runCatching { f.writeText(encoded) } }
            return encoded
        }

        /** Read-only snapshot of the fetch log (newest first) for the UI. */
        val fetchLog: List<FetchRecord> get() = _fetchLog.reversed()

        fun snapshotFetchLogJson(): String {
            val arr = JSONArray()
            for (r in fetchLog) {
                arr.put(
                    JSONObject()
                        .put("timestamp", r.timestamp)
                        .put("tool", r.tool)
                        .put("query", r.query ?: JSONObject.NULL)
                        .put("requestedUrl", r.requestedUrl)
                        .put("finalUrl", r.finalUrl ?: JSONObject.NULL)
                        .put("title", r.title)
                        .put("chars", r.chars)
                        .put("path", r.path)
                        .put("status", r.status),
                )
            }
            return arr.toString()
        }

        /** Result of a fetch+extract pipeline run (shared by both tools). */
        data class FetchOutcome(
            val finalUrl: String,
            val title: String,
            val text: String,
            val path: String,  // "http" or "webview"
            // Stripped-HTML fragment (WEB_SAFELIST) for the reader sidecar —
            // "" when no fragment was produced. The plain [text] is what the
            // host saves as .txt for RAG/chat; [html] is the display sidecar.
            val html: String = "",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Fetch-and-forget: cookies are DENIED by default (user directive, +75)
    // and flip ON only while a human check is pending ([CaptchaCookies]).
    // Service onCreate runs once per process, before any fetch — the plain
    // OkHttp client below carries no cookies either way (no CookieJar).
    override fun onCreate() {
        super.onCreate()
        CaptchaCookies.setAllowed(false)
        initFetchLogPersistence(this)
    }

    // Plain HTTP fast path. No CookieJar installed — OkHttp is STATELESS:
    // no cookies ride along on fetches, nothing accumulates per-site. The
    // "not a web browser" rule; only the WebView recovery path (a gated
    // human check) ever exchanges cookies.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IKenosisPlugin.Stub() {

        override fun getManifest(): String {
            // Built in Kotlin (not a string resource) — ONE representation of
            // the tool list; the host parses it with jsonDecode.
            return """
                {
                  "name": "Internet Search",
                  "version": "1.9.1",
                  "tools": [
                    {
                      "name": "browser_fetch",
                      "description": "Downloads a web page and returns its text content (truncated).",
                      "parameters": {
                        "url": {
                          "type": "string",
                          "description": "The absolute http(s) URL to fetch."
                        }
                      }
                    },
                    {
                      "name": "web_search",
                      "description": "Searches the web and returns the top result's text content (truncated).",
                      "parameters": {
                        "query": {
                          "type": "string",
                          "description": "The search query."
                        },
                        "engine": {
                          "type": "string",
                          "description": "Optional: google (default), duckduckgo, or qwant. Chosen by the host, not the model."
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()
        }

        override fun invoke(toolName: String, paramsJson: String): String {
            Log.i(TAG, "invoke($toolName) params=$paramsJson")
            return when (toolName) {
                "browser_fetch" -> invokeBrowserFetch(paramsJson)
                "web_search" -> invokeWebSearch(paramsJson)
                else -> err("Unknown tool: $toolName")
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Tool: browser_fetch (Phase-1.5 engine: fast path + WebView fallback) //
    // ------------------------------------------------------------------ //

    private fun invokeBrowserFetch(paramsJson: String): String {
        val params = try {
            JSONObject(paramsJson)
        } catch (e: Exception) {
            Log.w(TAG, "invokeBrowserFetch: bad params JSON: $e")
            return err("Parameters are not valid JSON.")
        }
        val url = normalizeSchemelessUrl(params.optString("url"))
        if (url.isEmpty()) return err("Missing required parameter: url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return err("url must be an absolute http(s) URL.")
        }
        val budget = effectiveObservationBudget(params.optInt("maxChars", 0))
        return try {
            // alwaysWebview: JS-rendered SPAs (yr.no, accuweather, windfinder)
            // can have >200 chars of static boilerplate that passes the thin-
            // text gate but misses the actual data. Always run the WebView and
            // keep the longer result — same as web_search.
            val outcome = fetchAndExtract(url, alwaysWebview = true)
            logFetch("browser_fetch", query = null, url, outcome)
            Log.i(
                TAG,
                "FC: [InternetPlugin] browser_fetch served via ${outcome.path} " +
                    "(${outcome.text.length} chars)",
            )
            okEnvelope(
                url = outcome.finalUrl,
                title = outcome.title,
                text = truncate(outcome.text, budget),
                html = outcome.html,
            )
        } catch (e: Exception) {
            Log.w(TAG, "browser_fetch($url) failed: $e")
            val msg = e.message ?: e.javaClass.simpleName
            // The app log keeps the RAW reason (describeFetchError in
            // main.dart glosses it); the envelope the MODEL reads gets the
            // plain-language class + the search-instead coach — on-device
            // 2026-09-26 the model invented dead URLs and got nothing back
            // that told it to search rather than retry.
            logFetchError("browser_fetch", query = null, url, "error:$msg")
            err(describeFetchFailure(msg, url))
        }
    }

    // ------------------------------------------------------------------ //
    // Tool: web_search (engine → top result → fetch)                      //
    // ------------------------------------------------------------------ //

    /**
     * web_search (+73): the HOST picks the engine — the `engine` param is
     * injected into the tool args by the host controller from a persisted
     * user setting (google / duckduckgo / qwant); the model never emits it.
     * Missing/unknown values fall back to google (today's behavior).
     *  - google: I'm-Feeling-Lucky — the 302 redirect resolves to the top
     *    result; an app-gated/missing pick falls back to the SERP anchor walk
     *    ([invokeWebSearchGoogle]).
     *  - duckduckgo: html.duckduckgo.com SERP → [resolveDdgResultUrls] →
     *    fetch the first OPENABLE result.
     *  - qwant: api.qwant.com v3 JSON → [resolveQwantResultUrls] → fetch.
     *    API rate-limits surface as a clear error envelope (HTTP <code>); a
     *    bot-blocked fast path (DataDome 403) retries the SERP in the hidden
     *    WebView ([fetchSerpBody]).
     */
    private fun invokeWebSearch(paramsJson: String): String {
        val params = try {
            JSONObject(paramsJson)
        } catch (e: Exception) {
            Log.w(TAG, "invokeWebSearch: bad params JSON: $e")
            return err("Parameters are not valid JSON.")
        }
        val query = params.optString("query").trim()
        if (query.isEmpty()) return err("Missing required parameter: query")
        val budget = effectiveObservationBudget(params.optInt("maxChars", 0))
        return when (parseEngineName(params.optString("engine"))) {
            "duckduckgo" -> invokeWebSearchEngine(
                query, "duckduckgo",
                DDG_HTML_URL.format(URLEncoder.encode(query, "UTF-8")),
                ::resolveDdgResultUrls,
                budget,
                serpIsJson = false,
            )
            "qwant" -> invokeWebSearchEngine(
                query, "qwant",
                QWANT_API_URL.format(URLEncoder.encode(query, "UTF-8")),
                ::resolveQwantResultUrls,
                budget,
                serpIsJson = true,
            )
            else -> invokeWebSearchGoogle(query, budget)
        }
    }

    /**
     * The google path: IFL's 302 resolves google's ONE pick — but that pick
     * can be unusable: +73 on-device, Google IFL's top result for the test
     * query was a facebook.com post whose page redirects a headless browser
     * to an `fb://native_post/…` deep link (ERR_UNKNOWN_URL_SCHEME — never
     * renders). IFL yields exactly ONE URL, so when the pick is app-gated
     * ([isAppGatedUrl]) or IFL answers without a redirect (ambiguous query →
     * SERP), the regular SERP is rendered in the WebView and the anchor walk
     * yields the ordered candidate list ([googleSerpCandidates] →
     * [pickGoogleResults]). TWO PHASES keep the common case a single fetch:
     *  (1) try the IFL pick alone — ONE cheap redirect-disabled request, no
     *      SERP walk. If it renders readable content, done (same cost as the
     *      pre-retry path).
     *  (2) IFL pick missing / app-gated / failed / thin → walk the regular
     *      SERP for the rest, skipping the IFL pick already tried, and hand
     *      the list to [fetchFirstReadable] which fetches candidates in order
     *      until one renders. Bounded so the IFL attempt + SERP attempts
     *      together stay under [MAX_SEARCH_CANDIDATES].
     */
    private fun invokeWebSearchGoogle(query: String, budget: Int): String {
        val iflUrl = GOOGLE_IFL_URL.format(URLEncoder.encode(query, "UTF-8"))
        // The IFL request ALWAYS goes out (resolveIflTarget below) — log it
        // so the fetch log shows the search itself, not only the result page.
        logSearchRequest(query, iflUrl)
        val iflTarget = resolveIflTarget(iflUrl)?.takeUnless { isAppGatedUrl(it) }
        // Phase 1: try the IFL pick alone — ONE cheap redirect-disabled
        // request, no SERP walk. The common case (IFL pick renders) stays a
        // single fetch, exactly like the pre-retry path.
        if (iflTarget != null) {
            val envelope = trySearchCandidate(iflTarget, query, "google", budget, isLast = false)
            if (envelope != null) return envelope
        }
        // Phase 2: IFL pick missing / app-gated / failed / thin → walk the
        // regular SERP for the ordered candidate list and try the rest,
        // skipping the IFL pick already tried. Bounded so the IFL attempt +
        // SERP attempts together stay under MAX_SEARCH_CANDIDATES.
        val serpUrl = GOOGLE_SERP_URL.format(URLEncoder.encode(query, "UTF-8"))
        val rest = googleSerpCandidates(query).filterNot { it == iflTarget }
        if (rest.isEmpty()) {
            Log.w(TAG, "FC: [InternetPlugin] web_search('$query'): no usable google result")
            logFetchError(
                "web_search", query = query, iflUrl,
                "error:no-usable-result (IFL pick unusable, SERP walk empty)",
            )
            return err(
                "google returned no results that the in-app browser can open " +
                    "for this query.",
            )
        }
        val cap = MAX_SEARCH_CANDIDATES - (if (iflTarget != null) 1 else 0)
        return fetchFirstReadable(
            query = query,
            candidates = rest,
            budget = budget,
            engineLabel = "google",
            serpUrl = serpUrl,
            emptyMessage = "google returned no results that the in-app " +
                "browser can open for this query.",
            maxAttempts = cap,
        )
    }

    /**
     * Resolve google IFL's pick WITHOUT downloading any page: ONE
     * redirect-disabled request — the 302 `Location` header IS the pick (or a
     * google.com/url interstitial, unwrapped via [unwrapGoogleRedirect]).
     * Null when google answers without a redirect (SERP for an ambiguous
     * query), the transport fails, or the location is not an absolute web
     * URL — the SERP walk ([googleSerpCandidates]) takes over in all those
     * cases.
     */
    private fun resolveIflTarget(googleUrl: String): String? {
        val noRedirectClient = http.newBuilder().followRedirects(false).build()
        val request = Request.Builder()
            .url(googleUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .build()
        return try {
            noRedirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location")
                    ?: return null // google answered 200 (SERP, no IFL pick)
                val target = unwrapGoogleRedirect(location) ?: location
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    target
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FC: [InternetPlugin] google IFL resolve failed: $e")
            null
        }
    }

    /**
     * Google's regular SERP, rendered in the WebView, anchors walked for
     * external result links ([WebViewPageFetcher.fetchLinks] →
     * [pickGoogleResults]). Returns the FULL ordered candidate list so the
     * caller can fall back to the NEXT result when the first can't be
     * fetched/rendered (a non-app-gated host that still 404s / bot-blocks /
     * renders blank — the case IFL's single pick can't recover from).
     * Empty when the render fails or every candidate is filtered (google's
     * own links, app-gated social hosts).
     */
    private fun googleSerpCandidates(query: String): List<String> {
        val serpUrl = GOOGLE_SERP_URL.format(URLEncoder.encode(query, "UTF-8"))
        logSearchRequest(query, serpUrl)
        val links = try {
            webviewFetcher.fetchLinks(serpUrl)
                .get(WEBVIEW_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "FC: [InternetPlugin] google SERP walk failed: $e")
            return emptyList()
        }
        val urls = links.second.split('\n').filter { it.isNotBlank() }
        val picks = pickGoogleResults(urls)
        Log.i(
            TAG,
            "FC: [InternetPlugin] google SERP walk: ${urls.size} links → " +
                "${picks.size} usable candidate(s)",
        )
        return picks
    }

    /** DDG/Qwant path: GET the SERP, resolve the RESULT URLs with [resolver],
     *  skip candidates that can never render ([isAppGatedUrl]), and walk the
     *  openable list in order via [fetchFirstReadable] — fetching each until
     *  one renders readable content (a non-app-gated host that 404s / bot-
     *  blocks / renders blank is skipped for the next result). A SERP that
     *  resolves to nothing (rate-limit page, empty results) is a clear error
     *  envelope — NOT a silent google fallback. [serpIsJson] enables the
     *  WebView SERP retry ([fetchSerpBody]) for JSON-API SERPs. */
    private fun invokeWebSearchEngine(
        query: String,
        engine: String,
        serpUrl: String,
        resolver: (String) -> List<String>,
        budget: Int,
        serpIsJson: Boolean,
    ): String {
        return try {
            logSearchRequest(query, serpUrl)
            val serpBody = fetchSerpBody(serpUrl, engine, serpIsJson)
            val all = resolver(serpBody)
            val candidates = all.filterNot { isAppGatedUrl(it) }
            if (all.size > candidates.size) {
                Log.i(
                    TAG,
                    "FC: [InternetPlugin] web_search[$engine]('$query'): skipped " +
                        "${all.size - candidates.size} app-gated result(s)",
                )
            }
            if (candidates.isEmpty()) {
                // +75: a zero-result body that smells like a bot-wall
                // challenge (HTTP-200 shape — DuckDuckGo's anomaly wall, or a
                // WebView-rendered DataDome interstitial) is NOT "no
                // results": arm the gate + return the actionable envelope.
                // The +74 failure read exactly like a benign empty SERP and
                // gave the user nothing to do.
                if (all.isEmpty() && looksLikeCaptchaPage(serpBody)) {
                    markCaptcha(engine, serpUrl, "challenge page served in place of results")
                    logFetchError("web_search", query = query, serpUrl, "error:${humanCheckMessage(engine)}")
                    return err(humanCheckMessage(engine))
                }
                // Log the miss — the SERP WAS fetched but resolved to nothing
                // openable (rate-limit page, empty results, all-app-gated,
                // blank challenge render). Without this record the plugin
                // UI's fetch log shows nothing for the round, which read as
                // "the search never ran" in the +73 live test.
                val status = if (all.isEmpty()) {
                    "error:no-results (serp ${serpBody.length} chars)"
                } else {
                    "error:all-results-app-gated (${all.size} results)"
                }
                Log.w(
                    TAG,
                    "FC: [InternetPlugin] web_search[$engine]('$query'): SERP resolved " +
                        "no openable result URL ($status)",
                )
                logFetchError("web_search", query = query, serpUrl, status)
                return err(
                    if (all.isEmpty()) {
                        "$engine returned no results for this query."
                    } else {
                        "$engine returned only results the in-app browser " +
                            "cannot open (app links) for this query."
                    },
                )
            }
            fetchFirstReadable(
                query = query,
                candidates = candidates,
                budget = budget,
                engineLabel = engine,
                serpUrl = serpUrl,
                emptyMessage = if (all.isEmpty()) {
                    "$engine returned no results for this query."
                } else {
                    "$engine returned only results the in-app browser " +
                        "cannot open (app links) for this query."
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "web_search[$engine]($query) failed: $e")
            val msg = e.message ?: e.javaClass.simpleName
            logFetchError("web_search", query = query, serpUrl, "error:$msg")
            err("Search failed: $msg")
        }
    }

    /**
     * Attempt one web_search result candidate through the shared
     * fetch+extract pipeline. Returns the SUCCESS envelope (ok, readable
     * text truncated to [budget]) when the candidate rendered enough
     * content ([acceptSearchResult]); null when the fetch FAILED (both paths
     * threw) or the text was too thin AND more candidates remain — the caller
     * should try the next result. On the LAST candidate a thin render is
     * accepted (a short real page beats an error envelope). Logs every
     * attempt to the in-process fetch log so the plugin UI shows each URL
     * tried and why it was skipped.
     */
    private fun trySearchCandidate(
        candidate: String,
        query: String,
        engineLabel: String,
        budget: Int,
        isLast: Boolean,
    ): String? {
        return try {
            val outcome = fetchAndExtract(candidate, alwaysWebview = true)
            if (!acceptSearchResult(outcome.text, isLast)) {
                Log.i(
                    TAG,
                    "FC: [InternetPlugin] web_search[$engineLabel]('$query') → $candidate " +
                        "rendered thin (${outcome.text.length} chars) — trying next result",
                )
                logFetchError("web_search", query, candidate, "thin:${outcome.text.length}chars")
                return null
            }
            logFetch("web_search", query = query, candidate, outcome)
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engineLabel]('$query') → ${outcome.finalUrl} " +
                    "served via ${outcome.path} (${outcome.text.length} chars)",
            )
            okEnvelope(
                url = outcome.finalUrl,
                title = outcome.title,
                text = truncate(outcome.text, budget),
                query = query,
                html = outcome.html,
            )
        } catch (e: Exception) {
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engineLabel]('$query') → $candidate " +
                    "failed (${e.message ?: e.javaClass.simpleName}) — trying next result",
            )
            logFetchError(
                "web_search", query, candidate,
                "error:${e.message ?: e.javaClass.simpleName}",
            )
            null
        }
    }

    /**
     * Walk the ordered result-candidate list ([trySearchCandidate] per URL)
     * until one renders readable content, then return its success envelope.
     * Bounded by [maxAttempts] (default [MAX_SEARCH_CANDIDATES]) — a run of
     * hung fetches is backstopped by the host invoke timeout, and
     * failed fetches usually fail fast, so the cap is rarely hit in
     * wall-clock terms. Surfaces [emptyMessage] (the caller's engine-specific
     * "no results" wording) when the list is empty; a distinct "every result
     * failed to load" envelope when candidates existed but none rendered.
     * The per-candidate attempts already logged each to the fetch log.
     */
    private fun fetchFirstReadable(
        query: String,
        candidates: List<String>,
        budget: Int,
        engineLabel: String,
        serpUrl: String,
        emptyMessage: String,
        maxAttempts: Int = MAX_SEARCH_CANDIDATES,
    ): String {
        val capped = candidates.take(maxAttempts.coerceAtLeast(0))
        if (capped.isEmpty()) {
            logFetchError("web_search", query, serpUrl, "error:no-openable-result ($engineLabel)")
            return err(emptyMessage)
        }
        for ((idx, candidate) in capped.withIndex()) {
            val isLast = idx == capped.lastIndex
            val envelope = trySearchCandidate(candidate, query, engineLabel, budget, isLast)
            if (envelope != null) return envelope
        }
        logFetchError(
            "web_search", query, serpUrl,
            "error:all-results-failed ($engineLabel, ${capped.size} tried)",
        )
        return err(
            "Search failed: every result $engineLabel returned failed to load " +
                "(${capped.size} tried).",
        )
    }

    // ------------------------------------------------------------------ //
    // Shared fetch+extract pipeline                                       //
    // ------------------------------------------------------------------ //

    /**
     * OkHttp fast path (10 s) + WebView fallback (15 s), keeping the longer
     * text. Shared by browser_fetch (direct URL) and web_search (Google IFL
     * redirect → final URL). Throws on both-paths-failed; the caller wraps the
     * exception into an error envelope.
     *
     * Google IFL unwrapping: Google's I'm Feeling Lucky sometimes lands on a
     * `google.com/url?q=<real-url>` interstitial HTML page instead of a clean
     * 302 to the target. When detected, the real URL is extracted from the `q`
     * parameter and the fast path re-runs on it — before the WebView fallback
     * check, so the fallback also targets the real page.
     *
     * [alwaysWebview]: when true, the WebView fallback ALWAYS runs (not just
     * when the fast path text is thin). Used by web_search because search
     * results are almost always JS-rendered SPAs — the fast path's static HTML
     * may have >200 chars of boilerplate but miss the actual data (e.g.
     * AccuWeather's temperature loads via JS). browser_fetch (user-supplied
     * URL) keeps the thin-text gate since static pages don't need the fallback.
     */
    private fun fetchAndExtract(url: String, alwaysWebview: Boolean = false): FetchOutcome {
        // FAST PATH (10 s hard): OkHttp + jsoup. A wedged call is cancelled so
        // it can't eat the WebView budget.
        val fastFuture = scope.future { fetchPageText(url) }
        val fastOutcome: Result<PageContent> = try {
            Result.success(fastFuture.get(HTTP_TIMEOUT_S, TimeUnit.SECONDS))
        } catch (e: Exception) {
            if (e is TimeoutException) fastFuture.cancel(true)
            Result.failure(e)
        }
        var fastResult = fastOutcome.getOrNull() // PageContent(finalUrl, title, text, html)
        val fastError = fastOutcome.exceptionOrNull()

        // GOOGLE REDIRECT UNWRAP: if the fast path landed on a google.com/url?
        // interstitial (common for IFL on mobile), extract the real URL from
        // the `q` param and re-fetch it. The interstitial page's text is just
        // a redirect stub (~200 chars), not the actual content.
        var effectiveUrl = url
        if (fastResult != null) {
            val realUrl = unwrapGoogleRedirect(fastResult.finalUrl)
            if (realUrl != null) {
                Log.i(TAG, "FC: [InternetPlugin] google redirect → $realUrl")
                effectiveUrl = realUrl
                val reFetchFuture = scope.future { fetchPageText(realUrl) }
                fastResult = try {
                    reFetchFuture.get(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    if (e is TimeoutException) reFetchFuture.cancel(true)
                    // Discard the interstitial stub: the unwrap target is still
                    // handed to the WebView fallback (effectiveUrl); if that
                    // fails too, the fetch fails CLEANLY rather than serving
                    // the google.com/url redirect stub text (~200 chars of
                    // "redirecting…" boilerplate) as page content.
                    null
                }
            }
        }

        // FALLBACK (gated): thin text / fast-path failure / alwaysWebview →
        // hidden WebView on the EFFECTIVE url (unwrapped), then keep the
        // LONGER text. alwaysWebview forces the fallback for web_search because
        // JS-rendered pages can have >200 chars of static boilerplate that
        // passes the thin-text gate but misses the actual data.
        var webviewResult: WebViewContent? = null
        var webviewError: Exception? = null
        // A transport-dead fast path (DNS / connect / unreachable) dooms the
        // WebView fallback too — a dead host serves no bot wall to beat.
        // Skipping it saves up to WEBVIEW_TIMEOUT_S of a guaranteed-nothing
        // render (device evidence 2026-09-26: one invented bbcdn.com URL
        // burned 10 s OkHttp + 15 s WebView for the same empty result).
        // Bot-wall fast errors (an HTTP response arrived) still retry.
        val fastTransportDead = isTransportDeadError(fastError)
        if (WEBVIEW_FALLBACK_ENABLED && !fastTransportDead &&
            (alwaysWebview || fastResult == null || shouldFallback(fastResult.text))
        ) {
            try {
                webviewResult = webviewFetcher
                    .fetch(effectiveUrl)
                    .get(WEBVIEW_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                webviewError = e
                Log.w(TAG, "webview path failed for $effectiveUrl: $e")
            }
        }

        return when {
            // When alwaysWebview forced the fallback, prefer the WebView result
            // regardless of length: the fast path's static HTML boilerplate can
            // be LONGER than the JS-rendered content (nav/footer/meta inflate
            // char count) but contain none of the actual data (weather numbers,
            // search results). Only fall back to OkHttp if WebView failed.
            webviewResult != null && (alwaysWebview || fastResult == null ||
                webviewResult.text.length > fastResult.text.length) ->
                // The JS only does the coarse script/style strip; run the WebView
                // innerHTML through the SAME jsoup WEB_SAFELIST as the fast path
                // so both branches share one allowlist (no <img>/<a>/attributes).
                FetchOutcome(
                    effectiveUrl,
                    webviewResult.title,
                    webviewResult.text,
                    "webview",
                    html = stripToWebFragment(webviewResult.html, effectiveUrl),
                )
            fastResult != null ->
                FetchOutcome(fastResult.finalUrl, fastResult.title, fastResult.text, "http", html = fastResult.html)
            else -> {
                // Prefer the WebView's failure reason: it is the meaningful
                // one for the user ("the page redirected to a non-web link
                // (fb://…) …") while the fast path's is often a bare
                // transport exception — the error envelope reaches the model,
                // which relays it as the search outcome.
                val cause = webviewError ?: fastError
                val msg = cause?.message
                    ?: cause?.javaClass?.simpleName
                    ?: "unknown error"
                Log.w(TAG, "fetchAndExtract($url) failed both paths: $msg")
                // RAW reason (no "Fetch failed:" prefix — the callers add
                // their own envelope wording; the prefix used to double up).
                throw IllegalStateException(msg)
            }
        }
    }

    /**
     * Detects Google redirect interstitial URLs (`google.com/url?q=<real-url>`)
     * and extracts the real target URL from the `q` query parameter. Returns
     * null for non-Google URLs or malformed `q` values.
     */
    private fun unwrapGoogleRedirect(url: String): String? {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return null
        if (!host.contains("google.com")) return null
        val q = uri.getQueryParameter("q") ?: return null
        return if (q.startsWith("http://") || q.startsWith("https://")) q else null
    }

    /** Appends a successful fetch record to the in-process URL log. */
    private fun logFetch(
        tool: String,
        query: String?,
        requestedUrl: String,
        outcome: FetchOutcome,
    ) {
        synchronized(_fetchLog) {
            _fetchLog.add(
                FetchRecord(
                    timestamp = System.currentTimeMillis(),
                    tool = tool,
                    query = query,
                    requestedUrl = requestedUrl,
                    finalUrl = outcome.finalUrl,
                    title = outcome.title,
                    chars = outcome.text.length,
                    path = outcome.path,
                    status = "ok",
                ),
            )
            if (_fetchLog.size > FETCH_LOG_CAP) _fetchLog.removeAt(0)
            persistFetchLog()
        }
    }

    /** Appends a FAILED fetch record — the URL log shows ALL attempts
     *  (success and failure) with HTTP status codes / error reasons, so users
     *  can see exactly what the plugin requested and what happened. */
    private fun logFetchError(
        tool: String,
        query: String?,
        requestedUrl: String,
        error: String,
    ) {
        synchronized(_fetchLog) {
            _fetchLog.add(
                FetchRecord(
                    timestamp = System.currentTimeMillis(),
                    tool = tool,
                    query = query,
                    requestedUrl = requestedUrl,
                    finalUrl = null,
                    title = "",
                    chars = 0,
                    path = "",
                    status = error,
                ),
            )
            if (_fetchLog.size > FETCH_LOG_CAP) _fetchLog.removeAt(0)
            persistFetchLog()
        }
    }

    /** Appends a SEARCH-REQUEST record — the engine round-trip itself (google
     *  IFL, the google/DDG SERP render, the qwant API call). The URL carries
     *  the encoded query, so the plugin UI shows searches too, not only the
     *  result pages fetched afterwards. status "search" renders as a neutral
     *  (non-error) row in the plugin app's log. */
    private fun logSearchRequest(query: String, requestedUrl: String) {
        synchronized(_fetchLog) {
            _fetchLog.add(
                FetchRecord(
                    timestamp = System.currentTimeMillis(),
                    tool = "web_search",
                    query = query,
                    requestedUrl = requestedUrl,
                    finalUrl = null,
                    title = "search request",
                    chars = 0,
                    path = "",
                    status = "search",
                ),
            )
            if (_fetchLog.size > FETCH_LOG_CAP) _fetchLog.removeAt(0)
            persistFetchLog()
        }
    }

    /** Builds the success envelope: {"ok":true,"data":{url,title,text[,query][,html]}}.
     *  [html] is the stripped-HTML reader sidecar (WEB_SAFELIST) — omitted when
     *  null/empty so old hosts that don't know the key ignore it gracefully. */
    private fun okEnvelope(
        url: String,
        title: String,
        text: String,
        query: String? = null,
        html: String? = null,
    ): String {
        val data = JSONObject()
            .put("url", url)
            .put("title", title)
            // data.text is ALREADY truncated to the observation budget —
            // the host enforces a second Dart-side cap.
            .put("text", text)
        if (query != null) data.put("query", query)
        // Truncate the sidecar to MAX_TEXT_CHARS (500k) — the reader cap
        // (the host's max-raw-body-chars limit) is the same, so the full
        // fragment reaches the host. Capped to bound the plugin→host IPC
        // envelope; the LLM never sees this field (display-only sidecar).
        if (!html.isNullOrEmpty()) data.put("html", truncate(html, MAX_TEXT_CHARS))
        return JSONObject().put("ok", true).put("data", data).toString()
    }

    // The WebView fetcher starts lazily (its HandlerThread costs a thread —
    // don't pay for it until a fallback actually fires). Created under a lock
    // because invoke() runs on the binder thread pool.
    private val webviewFetcherLock = Any()
    private var _webviewFetcher: WebViewPageFetcher? = null
    private val webviewFetcher: WebViewPageFetcher
        get() = synchronized(webviewFetcherLock) {
            _webviewFetcher ?: WebViewPageFetcher(this).also { _webviewFetcher = it }
        }

    /** Arms [CaptchaGate] for [engine] and posts the check-needed system
     *  notification (deduped per engine inside [CaptchaNotifications]).
     *  The solve URL is the blocked URL's SITE ORIGIN ([solveUrlForHuman]) —
     *  the visible WebView must show a page a human can act on, not a JSON
     *  API body. Cookies turn ON here (the clearance flow is the plugin's
     *  only legitimate cookie consumer — see [CaptchaCookies]) and OFF again
     *  at every clear below. */
    private fun markCaptcha(engine: String, url: String, reason: String) {
        val now = System.currentTimeMillis()
        val solveUrl = solveUrlForHuman(url)
        CaptchaGate.set(engine, solveUrl, reason, now)
        CaptchaCookies.setAllowed(true)
        CaptchaNotifications.notify(this, engine, solveUrl, now)
        Log.w(TAG, "FC: [InternetPlugin] human check needed for $engine ($reason) — gate armed")
    }

    /** SERP body fetch with a bot-block fallback. Plain OkHttp is fast but
     *  its TLS fingerprint is trivially flagged by SERP-edge bot walls —
     *  Qwant's DataDome returned HTTP 403 on-device for api.qwant.com
     *  (2026-09-15, +73 live test) while the SAME URL + UA passed from a
     *  desktop IP, so the block is fingerprint/reputation based, not the
     *  request shape. The hidden WebView is a real Chrome with a real TLS
     *  stack — the exact machinery that has served the google IFL path
     *  on-device for months — so a blocked SERP is re-fetched in the
     *  renderer:
     *   - JSON APIs (qwant) via [fetchAndExtract] — Chrome renders
     *     application/json as text; [resolveQwantResultUrls] tolerates the
     *     viewer's toolbar text.
     *   - HTML SERPs (duckduckgo, +75) via [WebViewPageFetcher.fetchHtml] —
     *     the raw post-JS DOM, because the innerText walk strips the anchor
     *     markup [resolveDdgResultUrls] parses. Before +75 HTML SERPs kept
     *     the honest HTTP error, so a bot-walled duckduckgo never recovered
     *     even after the user solved a challenge.
     *
     *  +75 the retry is also the CAPTCHA RECOVERY PATH: the clearance cookie
     *  the user earns solving the challenge in the plugin app's VISIBLE
     *  WebView lives in the process-wide CookieManager — this retry sends
     *  it, the engine serves the real SERP, and the gate clears. On a
     *  bot-wall signature ([isBotWallHttpError], or a retry that still
     *  returns challenge HTML) the gate arms ([markCaptcha]) and the error
     *  envelope tells the user what to do ([humanCheckMessage]) — the +74
     *  failure surfaced a bare "bot protection may be blocking this device"
     *  with nothing to act on. A successful fast path clears the gate: the
     *  engine answers, any pending check is moot. */
    private fun fetchSerpBody(serpUrl: String, engine: String, serpIsJson: Boolean): String {
        try {
            val body = fetchRawBody(serpUrl)
            CaptchaGate.clear(engine)
            CaptchaCookies.setAllowed(false) // check resolved — back to deny-by-default
            return body
        } catch (e: Exception) {
            val botWall = isBotWallHttpError(e)
            if (botWall) markCaptcha(engine, serpUrl, e.message ?: "blocked")
            if (!botWall && !serpIsJson) throw e
            Log.w(TAG, "web_search[$engine] serp fast path failed ($e) — retrying SERP in the webview")
            val body: String = try {
                if (serpIsJson) {
                    fetchAndExtract(serpUrl, alwaysWebview = true).text
                } else {
                    webviewFetcher.fetchHtml(serpUrl)
                        .get(WEBVIEW_TIMEOUT_S, TimeUnit.SECONDS)
                }
            } catch (e2: Exception) {
                Log.w(TAG, "web_search[$engine] serp webview retry failed: $e2")
                if (botWall) {
                    throw IllegalStateException(humanCheckMessage(engine))
                }
                throw IllegalStateException(
                    "the search engine blocked or could not serve its results " +
                        "(its bot protection may be blocking this device)",
                )
            }
            if (botWall && looksLikeCaptchaPage(body)) {
                // The retry landed on the challenge itself (no earned cookie
                // yet) — surface it as the check it is, not as garbage text.
                throw IllegalStateException(humanCheckMessage(engine))
            }
            if (botWall) {
                // The retry got REAL results — the earned clearance cookie
                // worked; the check is satisfied. Cookies back to denied
                // (the earned jar stays on disk, dormant — see CaptchaCookies).
                CaptchaGate.clear(engine)
                CaptchaCookies.setAllowed(false)
            }
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engine] serp served via webview " +
                    "(${body.length} chars)",
            )
            return body
        }
    }

    /** OkHttp GET → raw body string (no HTML→text extraction). Used by the
     *  DDG/Qwant SERP resolution (+73): the body is a search-results page /
     *  JSON envelope, not page content. Throws on non-2xx ("HTTP 403") so a
     *  rate-limited engine surfaces as a clear error envelope. */
    private fun fetchRawBody(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IllegalStateException("Empty response body")
        }
    }

    /** Fast-path extraction result: the page title + plain text (for the .txt
     *  the host saves + RAG/chat grounds on) + the stripped-HTML fragment (the
     *  reader sidecar; WEB_SAFELIST). */
    private data class PageExtract(val title: String, val text: String, val html: String)

    /** Fast-path fetch result: the final (redirect-resolved) URL + the
     *  [PageExtract] payload. */
    private data class PageContent(val finalUrl: String, val title: String, val text: String, val html: String)

    private fun fetchPageText(url: String): PageContent {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty response body")
            val capped = if (body.length > MAX_FETCH_BYTES) {
                body.substring(0, MAX_FETCH_BYTES.toInt())
            } else {
                body
            }
            val finalUrl = response.request.url.toString()
            val ex = extractText(capped, finalUrl)
            return PageContent(finalUrl, ex.title, ex.text, ex.html)
        }
    }

    /** jsoup HTML → readable text: drop script/style/nav noise, keep block
     *  text lines (headings, paragraphs, list items). Throwaway Phase-1
     *  extractor — a future headless-render engine returns rendered text natively.
     *  Also produces a stripped-HTML fragment ([PageExtract.html]) from the
     *  SAME cleaned body for the reader sidecar (WEB_SAFELIST) — so the plain
     *  text and the rendered HTML always describe the same content. */
    private fun extractText(html: String, url: String): PageExtract {
        val doc = Jsoup.parse(html, url)
        val title = doc.title().trim()
        // jsoup 1.18.1's Document.body() is non-nullable (creates an empty
        // <body> if absent), so no null guard — the fragment/text are just empty
        // for a bodyless document.
        val body = doc.body()
        body.select("script, style, noscript, nav, header, footer, aside, form, svg, iframe")
            .remove()
        // Strip the cleaned body to the reader allowlist AFTER the noise removal
        // above (less to clean, and nav/header/footer text is gone). Jsoup.clean
        // keeps the text inside dropped tags (div/span/a) and removes only the
        // disallowed tags, so no visible content is lost — only markup noise.
        val fragment = stripToWebFragment(body.html(), url)
        val sb = StringBuilder()
        val blocks = body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td, th, figcaption, dt, dd")
        if (blocks.isEmpty()) {
            // Minimal fallback: whole-body text.
            sb.append(body.wholeText().trim())
        } else {
            for (el in blocks) {
                val line = el.ownText().ifBlank { el.wholeText() }.trim()
                if (line.isEmpty()) continue
                // Headings get a blank line around them for structure.
                if (el.tagName()[0] == 'h') {
                    sb.append("\n").append(line.uppercase()).append("\n\n")
                } else {
                    sb.append(line).append("\n")
                }
                if (sb.length > OBSERVATION_BUDGET_CHARS * 4) break // bound the loop
            }
        }
        return PageExtract(title, sb.toString().trim(), fragment)
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                            //
    // ------------------------------------------------------------------ //

    /** Observation budget: cut at the [budget] boundary (no ellipsis — the
     *  host model treats the text as page content, not as a styled quote).
     *  [budget] is the host's per-round scaled value, already clamped by
     *  [effectiveObservationBudget]. */
    private fun truncate(text: String, budget: Int): String =
        if (text.length <= budget) {
            text
        } else {
            text.substring(0, budget)
        }

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    override fun onDestroy() {
        synchronized(webviewFetcherLock) {
            _webviewFetcher?.close()
            _webviewFetcher = null
        }
        scope.cancel()
        super.onDestroy()
    }
}