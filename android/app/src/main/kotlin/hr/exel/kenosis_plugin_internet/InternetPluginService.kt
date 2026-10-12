package hr.exel.kenosis_plugin_internet

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import hr.exel.kenosis.plugin.IKenosisPlugin
import hr.exel.kenosis.plugin.IKenosisProgressListener
import java.io.File
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Kenosis plugin service: bound by the offline Kenosis host over binder
 * (AIDL `IKenosisPlugin`, contract file copied into both apps). This app is
 * the ONLY component of the ecosystem holding INTERNET.
 *
 * Tools (tier ladder / ToS first — plan "anonymous-first-tiered-web-fetch"):
 *  - browser_fetch(url): fetch with the least-powerful honest client —
 *    Tier 1 anonymous HTTPS GET ([WebFetchTiers]) / escalate one tier on an
 *    honest failure to Tier 2: a VISIBLE tab's post-JS text (what the user
 *    sees — WYSIWYG). A wall neither can pass becomes Tier 3 — the failure
 *    IS the answer and the tab stays open for the user. Text truncated to
 *    the observation budget before returning.
 *  - web_search(query): the HOST picks the engine (google / duckduckgo /
 *    qwant). The ENGINE flow is WebView-only (SERP tab, stock UA, JS on —
 *    ToS): a SERP tab opens as proof of what was asked. The RESULT pages
 *    the SERP points to are read through the same tier ladder (Tier 1
 *    first; a tab opens only on escalation) and up to
 *    [MAX_SEARCH_CANDIDATES] pages are KEPT per round (multi-gather — no
 *    first-accepted-wins): the tier-1 candidates fetch in ONE parallel
 *    batch, uncovered ones fall back to the Lucky tab, and every kept
 *    segment is served to the model as a `[Source N/M]` page (host side).
 *
 * No engine URL ever meets the anonymous client ([TabManager.isEngineUrl]
 * routes them straight to the tab), no IFL backdoor, no UA stripping or
 * spoofing, no captcha cookie-unlock loop, no bypass of any bot wall or
 * consent gate. Captcha = notification + the human solves it in the visible
 * tab ([CaptchaGate] / [CaptchaNotifications]).
 */
class InternetPluginService : Service() {

    /** One kept search-result segment (multi-gather): where the fetch was
     *  ASKED to go, where it actually landed (post-redirect), the page
     *  title, the extracted text, and the path vocabulary ("anon" = Tier 1
     *  anonymous GET, "tab" = Tier 2 visible tab). Class-level (not in the
     *  companion) so tests reference it as InternetPluginService.SearchSegment
     *  — companion-nested classes do not shorten through the outer name. */
    data class SearchSegment(
        val requestUrl: String,
        val finalUrl: String,
        val title: String,
        val text: String,
        val path: String,
    )

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
         *  saved verbatim to the Files library and chatted about via RAG. */
        const val MAX_TEXT_CHARS = 500000

        /** +73 j: the host scales the per-round observation budget to its
         *  free context window and sends it as the `maxChars` tool arg.
         *  Missing/stale values (0, negative — e.g. an old host that never
         *  sends it) keep today's fixed 6 KB; an out-of-range value clamps
         *  to the floor/ceiling. */
        fun effectiveObservationBudget(maxChars: Int): Int {
            if (maxChars <= 0) return OBSERVATION_BUDGET_CHARS
            return maxChars.coerceIn(OBSERVATION_BUDGET_CHARS, MAX_TEXT_CHARS)
        }

        /** Max result candidates a single web_search SERVES (multi-gather):
         *  the fan-out cap for the tier-1 parallel batch AND the walk cap
         *  for the tier-2 Lucky-tab ladder — at most [MAX_SEARCH_CANDIDATES]
         *  segments go out as `[Source N/M]` pages. Each tier-2 render is
         *  capped at WebViewPageFetcher.RENDER_TIMEOUT_S (15 s), so three is
         *  a hard ceiling under the host's invoke timeout;
         *  [SEARCH_ROUND_BUDGET_MS] also stops the ladder early. The ladder
         *  stops as soon as the cap is met. */
        const val MAX_SEARCH_CANDIDATES = 3

        /** Wall-clock budget for one web_search round's RESULT walk (the
         *  tier-2 ladder; the tier-1 fan-out runs BEFORE this gate and pays
         *  its ≈6 s on top — fast-path ceiling, WebFetchTiersTest).
         *  Applies to EVERY render start, including the last candidate — the
         *  old `!isLast` bypass could open the 3rd page at t≈24 s and the
         *  host timed out mid-render (2026-10-07).
         *  28_000 (re-derived for multi-gather): after a slow SERP (15 s) +
         *  the fan-out (≈6 s) elapsed is 21 s — the gate admits a fallback
         *  render; worst compose stays under the host's 45 s invoke timeout:
         *  SERP 15 + fan-out 6 (both before the gate), the gate admits a
         *  render at elapsed 21 ≤ 28, plus one full 15 s render = 36 s.
         *  Pinned: SEARCH_ROUND_BUDGET_MS + RENDER_TIMEOUT_S*1000 ≤ 44_000
         *  (WebFetchTiersTest). */
        const val SEARCH_ROUND_BUDGET_MS = 28_000L

        /** Pure: has the tier-2 tab ladder blown the round wall clock? The
         *  gate admits a render AT the boundary (strictly >) — pinned both
         *  sides in SearchMultiGatherTest. */
        fun pastRoundBudget(startedAtMs: Long, nowMs: Long): Boolean =
            nowMs - startedAtMs > SEARCH_ROUND_BUDGET_MS

        /** In-flight `invoke` count. Wipe-on-close must not destroy the live
         *  tab WebViews while a tool still owns them (see
         *  [BrowserSettings.shouldWipeOnDestroy]). */
        private val inFlightInvokes = java.util.concurrent.atomic.AtomicInteger(0)

        fun beginInvoke() {
            inFlightInvokes.incrementAndGet()
        }

        fun endInvoke() {
            inFlightInvokes.decrementAndGet()
        }

        /** True while `web_search` / `browser_fetch` is still reading tabs. */
        fun isInvokeInFlight(): Boolean = inFlightInvokes.get() > 0

        /** Observation budget: cut at the [budget] boundary (no ellipsis — the
         *  host model treats the text as page content, not as a styled quote).
         *  [budget] is the host's per-round scaled value, already clamped by
         *  [effectiveObservationBudget]. Companion so the pure envelope
         *  helpers can use it (instance code calls it unqualified too). */
        fun truncate(text: String, budget: Int): String =
            if (text.length <= budget) {
                text
            } else {
                text.substring(0, budget)
            }

        /** Pure: the TIER-2 fallback's acceptance predicate — accept this
         *  candidate's tab extract, or try the next SERP link? A blank render
         *  is a failed page (challenge shell, empty SPA) — try the next. A
         *  THIN but non-blank render is accepted even mid-list: the tab shows
         *  the same thin page to the user (honest WYSIWYG), and there is no
         *  hidden second renderer to do better. The last candidate is always
         *  accepted (a short real page beats an error envelope). Byte-identical
         *  to the pre-multi-gather predicate. */
        fun acceptCandidateExtract(text: String, isLast: Boolean): Boolean =
            text.isNotBlank() || isLast

        /**
         * Pure, unit-testable: assemble the kept TIER-1 segments from the
         * fan-out outcomes, in engine order (no re-ranking). A candidate with
         * an absent / null (failed) / not-Ready outcome yields nothing but
         * does NOT stop assembly — a dead candidate never starves the others
         * (the first-accepted-wins bug this replaces let candidate #1's
         * 1.2 KB of player chrome beat 9 unread candidates). Dedupe by FINAL
         * url (redirects collapse); the better-ranked candidate wins its
         * slot. Engine-rank order is preserved end to end.
         */
        fun selectTier1Segments(
            candidates: List<String>,
            outcomes: Map<String, WebFetchTiers.Tier1Response?>,
        ): List<SearchSegment> {
            val out = mutableListOf<SearchSegment>()
            val seenFinalUrls = mutableSetOf<String>()
            for (candidate in candidates) {
                val t1 = outcomes[candidate] ?: continue
                val page = HtmlTextExtractor.extract(t1.rawHtml)
                if (WebFetchTiers.decideAfterTier1(t1.status, page.text, t1.rawHtml) !=
                    WebFetchTiers.TierDecision.Ready
                ) {
                    continue
                }
                if (!seenFinalUrls.add(t1.finalUrl)) continue
                out.add(SearchSegment(candidate, t1.finalUrl, page.title, page.text, path = "anon"))
            }
            return out
        }

        /**
         * Pure, unit-testable: the multi-source SUCCESS envelope — the one
         * envelope shape for both tools now. Every kept segment becomes one
         * entry in `data.results[]` ({url,title,text}); `data.url/title/text`
         * mirror results[0] (the primary) so an old host reading the flat
         * fields — or a new host reading an old plugin's flat-only envelope —
         * still works. Per-segment text is truncated at [budget] (the
         * per-segment ceiling; the host applies its own compose-time budget
         * across sources). A single segment (browser_fetch, or a 1-hit
         * search) is today's shape plus a 1-entry array.
         */
        fun searchEnvelope(
            segments: List<SearchSegment>,
            query: String?,
            budget: Int,
        ): String {
            val primary = segments.firstOrNull()
            val data = JSONObject()
                .put("url", primary?.finalUrl ?: "")
                .put("title", primary?.title ?: "")
                // data.text is ALREADY truncated to the per-segment budget —
                // the host enforces a second Dart-side cap.
                .put("text", primary?.let { truncate(it.text, budget) } ?: "")
            val results = JSONArray()
            for (segment in segments) {
                results.put(
                    JSONObject()
                        .put("url", segment.finalUrl)
                        .put("title", segment.title)
                        .put("text", truncate(segment.text, budget)),
                )
            }
            data.put("results", results)
            if (query != null) data.put("query", query)
            return JSONObject().put("ok", true).put("data", data).toString()
        }

        /** Pure: should a web_search candidate be opened at all? */
        // (kept tiny — the real filters live in pickSerpCandidates)

        /**
         * Pure, unit-testable: one progress-event payload — the exact JSON
         * string pushed over IKenosisProgressListener.onProgress.
         * `{"seq":N,"code":"…","tMs":ms,"params":{…}}`. [tMs] is
         * plugin-relative (SystemClock.elapsedRealtime()) for logcat
         * correlation ONLY — the host displays its own arrival-clock
         * timestamps (no clock-domain mixing). Display-only progress facts:
         * params carry counts/statuses/short host labels, never page text or
         * query content (that stays in the observation envelope / FC: logs).
         */
        fun progressPayload(seq: Long, code: String, tMs: Long, params: JSONObject): String =
            JSONObject()
                .put("seq", seq)
                .put("code", code)
                .put("tMs", tMs)
                .put("params", params)
                .toString()

        /**
         * Pure: is [url] one of the engine's OUTBOUND RESULT REDIRECTORS?
         * Google `/url?…` (plaintext interstitial) and `/goto?url=CAES-…`
         * (2026-10-07 CDP: the encrypted-token form Google now serves for
         * organic results — NOT client-unwrappable); DuckDuckGo `/l/?uddg=…`;
         * Qwant `/redirect?…`. [resolveResultTarget] attempts the unwrap on
         * these paths. Public for the unit-test pin.
         *
         * DELEGATED ([TabManager.isEngineRedirector] is the single source —
         * the same single-source discipline as [TabManager.isEngineUrl]): the
         * tier ladder's skipTier1 carves this family out for Tier-1, so the
         * predicate and the carve-out must never drift apart. Kept as a
         * companion alias because several pure helpers + pins reference it.
         */
        fun isEngineRedirector(url: String): Boolean =
            TabManager.isEngineRedirector(url)

        /**
         * Pure: is [url] a redirector we can NAVIGATE even though we cannot
         * unwrap it client-side? Google's 2026-10-07 `/goto?url=CAES-…` is
         * exactly this — the target is an encrypted blob (CDP showed no
         * plaintext in href / ping / data-*), but loading the goto URL in the
         * WebView follows Google's 302 to the real page. Other un-unwrappable
         * redirectors (`/l/?uddg=not-a-url`, `/url?q=garbage`) are dead and
         * stay engine-own so they are dropped. Public for the unit-test pin.
         */
        fun isNavigableResultRedirector(url: String): Boolean {
            val uri = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
            val host = uri.host?.lowercase() ?: return false
            val path = uri.path ?: ""
            if (!host.contains("google") || !path.startsWith("/goto")) return false
            val query = uri.rawQuery ?: return false
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                if (pair.substring(0, eq) != "url") continue
                return pair.substring(eq + 1).isNotEmpty()
            }
            return false
        }

        /**
         * Pure: unwrap a SERP redirect href to its result target.
         *  DuckDuckGo html SERPs wrap results as `/l/?uddg=<enc>&rut=…`;
         *  Google's `/url?q=` / `/url?url=` interstitials and Qwant's
         *  `/redirect?…` do the same. Google's `/goto?url=CAES-…` (2026-10-07)
         *  carries an ENCRYPTED token, not a URL — the unwrap loop finds no
         *  http(s) target and returns the goto URL as-is, still navigable
         *  (the WebView follows Google's 302 to the real page). The unwrap is
         *  scoped to those known redirector PATHS on the engine hosts — a
         *  random `?q=` on a result page is left alone. Unparseable / non-web
         *  values come back as-is. */
        fun resolveResultTarget(raw: String): String {
            var url = raw.trim()
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http://") && !url.startsWith("https://")) return url
            if (!isEngineRedirector(url)) return url
            val uri = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return url
            }
            val query = uri.rawQuery ?: return url
            for (key in listOf("uddg", "url", "q")) {
                for (pair in query.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq <= 0) continue
                    if (pair.substring(0, eq) != key) continue
                    val decoded = try {
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    } catch (e: Exception) {
                        null
                    }
                    if (decoded != null &&
                        (decoded.startsWith("http://") || decoded.startsWith("https://"))
                    ) {
                        return decoded
                    }
                }
            }
            return url
        }

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
         *  (bypassing the search engine) instead of erroring. Prepends
         *  `https://` only when the WHOLE string is a domain; anything else
         *  (prose, garbage, already-schemed) returns unchanged, so the
         *  downstream scheme check still rejects non-URLs with today's error. */
        fun normalizeSchemelessUrl(raw: String): String {
            val v = raw.trim()
            if (v.startsWith("http://") || v.startsWith("https://")) return v
            return if (SCHEMELESS_URL.matches(v)) "https://$v" else v
        }

        /**
         * Hosts whose web pages redirect a browser into an app deep link
         * (fb://, intent://) — the page can NEVER render in the tab, so a
         * candidate on these hosts is skipped BEFORE wasting a tab on it
         * (+73 on-device case: a facebook.com post redirected to
         * `fb://native_post/…` and the load died with ERR_UNKNOWN_URL_SCHEME).
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

        /** Pure: is [url] the SEARCH ENGINE's own link (nav, sign-in, ads,
         *  pagination)? Covers national Google TLDs, static/CDN hosts that
         *  ride the SERP, and ad networks. NAVIGABLE result redirectors
         *  (Google `/goto?url=CAES-…`) are NOT engine-own — they are result
         *  pointers (see [isNavigableResultRedirector]); dropping them is
         *  what turned the 2026-10-07 `/goto?url=CAES-…` SERP into
         *  `37 links → 0 usable`. Un-unwrappable garbage redirectors stay
         *  engine-own and are dropped. */
        private fun isEngineOwnUrl(url: String, engine: String): Boolean {
            if (isNavigableResultRedirector(url)) return false
            val host = hostOf(url) ?: return true
            return when (engine.lowercase()) {
                "duckduckgo" -> host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")
                "qwant" -> host == "qwant.com" || host.endsWith(".qwant.com")
                else -> host.contains("google") ||
                    host.endsWith(".doubleclick.net") ||
                    host.endsWith(".gstatic.com") ||
                    host.endsWith(".ggpht.com")
            }
        }

        /**
         * Pure, unit-testable: put result-shaped hrefs first so a chrome-heavy
         * SERP walk (mobile Google's ~40 nav/footer links before the organic
         * results) cannot starve [pickSerpCandidates]. Result-shaped =
         * survives [resolveResultTarget] and is neither engine-own nor
         * app-gated. Ties keep input order. Public for the unit-test pin.
         */
        fun rankSerpHrefs(rawUrls: List<String>, engine: String): List<String> {
            val results = mutableListOf<String>()
            val other = mutableListOf<String>()
            for (raw in rawUrls) {
                val url = resolveResultTarget(raw)
                val usable = url.startsWith("https://") &&
                    !isEngineOwnUrl(url, engine) &&
                    !isAppGatedUrl(url)
                if (usable) results.add(raw) else other.add(raw)
            }
            return results + other
        }

        /** Pure: `host/path…` (capped) for a walk-empty diagnostic line. */
        fun sampleHref(raw: String): String {
            return try {
                val uri = java.net.URI(raw)
                val host = uri.host ?: "?"
                val path = (uri.path ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
                "$host${path.take(40)}"
            } catch (_: Exception) {
                raw.take(48)
            }
        }

        /**
         * Pure, unit-testable: the ordered list of USABLE result URLs from a
         * SERP anchor walk — rank result-shaped hrefs first
         * ([rankSerpHrefs]), unwrap engine redirectors, drop the engine's own
         * links and app-gated social hosts, https only, order kept. Empty when
         * every candidate is filtered. The full list lets the caller fall back
         * to the NEXT result when the first renders blank / errors (the Lucky
         * tab re-navigates; the SERP tab stays as proof). Public for the
         * unit-test pin.
         */
        fun pickSerpCandidates(rawUrls: List<String>, engine: String): List<String> {
            val out = LinkedHashSet<String>()
            for (raw in rankSerpHrefs(rawUrls, engine)) {
                val url = resolveResultTarget(raw)
                if (!url.startsWith("https://")) continue
                if (isEngineOwnUrl(url, engine)) continue
                if (isAppGatedUrl(url)) continue
                out.add(url)
                if (out.size >= 10) break
            }
            return out.toList()
        }

        /** Pure: [pickSerpCandidates] for the google engine — the alias the
         *  unit tests and the google SERP walk use. */
        fun pickGoogleResults(urls: List<String>): List<String> =
            pickSerpCandidates(urls, "google")

        /** Pure: [pickGoogleResults].firstOrNull() — the single-pick alias. */
        fun pickGoogleResult(urls: List<String>): String? =
            pickGoogleResults(urls).firstOrNull()

        /**
         * DuckDuckGo html SERP → RESULT URLs in order (retargeted for the
         * visible-tab model: the caller feeds the SERP tab's LINK_WALK_JS
         * hrefs, not raw HTML — no HTML scrape client). Unwraps
         * `/l/?uddg=<url-encoded-target>&rut=…` via [resolveResultTarget].
         * Public for the unit-test pin.
         */
        fun resolveDdgResultUrls(rawUrls: List<String>): List<String> =
            pickSerpCandidates(rawUrls, "duckduckgo")

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
         * Pure, unit-testable: does this page text/HTML look like a bot-wall
         * challenge rather than results? Applied to the VISIBLE tab's extract
         * when the SERP walk yields ZERO usable links — the body came back
         * shaped but empty, which is exactly what a challenge page parses to.
         * Distinguishes "no results for this query" from "the engine wants a
         * human check".
         */
        fun looksLikeCaptchaPage(body: String): Boolean {
            if (body.isEmpty()) return false
            val probe = body.take(100_000).lowercase()
            return CAPTCHA_MARKERS.any { it in probe }
        }

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

        /**
         * In-process URL log — every browser_fetch / web_search call appends a
         * record, and every search round records the SEARCH-ENGINE request
         * itself (the SERP URL carrying the encoded query) via
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
         *  rehydrating from fetch_log.json cannot resurrect the cleared rows.
         *  Returns the JSON persisted (public for the unit-test pin). */
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

        /** Result of a tier-ladder fetch run (shared by both tools).
         *  [path] is the log vocabulary for how the text was obtained:
         *  "anon" = Tier 1 (anonymous OkHttp GET, no tab opened), "tab" =
         *  Tier 2 (the visible tab rendered it). */
        data class FetchOutcome(
            val finalUrl: String,
            val title: String,
            val text: String,
            val path: String = "tab",
        )
    }

    override fun onCreate() {
        super.onCreate()
        BrowserSettings.init(this)
        initFetchLogPersistence(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ------------------------------------------------------------------ //
    // Progress push (IKenosisProgressListener — plugin → host)            //
    // ------------------------------------------------------------------ //

    /** The host's one-way progress listener (see `setProgressListener`).
     *  Volatile: written on a binder thread (registration), read from every
     *  emitter thread (binder, WebView main, OkHttp workers). */
    @Volatile private var progressListener: IKenosisProgressListener? = null

    /** Monotonic per-plugin event counter — the host drops stale events. */
    private val progressSeq = AtomicLong(0)

    /** Emits one progress event. No-op when no host listener is registered
     *  (old host — it never calls setProgressListener) and best-effort on a
     *  dead one: oneway IPC + catch-all, so an emitter NEVER blocks or fails
     *  on progress. Display-only facts only (see [progressPayload]). */
    private fun progress(code: String, params: JSONObject = JSONObject()) {
        val listener = progressListener ?: return
        try {
            listener.onProgress(
                progressPayload(
                    progressSeq.incrementAndGet(),
                    code,
                    SystemClock.elapsedRealtime(),
                    params,
                ),
            )
        } catch (_: Throwable) {
            // Dead / binder-limited peer — progress is best-effort by contract.
        }
    }

    private val binder = object : IKenosisPlugin.Stub() {

        override fun setProgressListener(l: IKenosisProgressListener?) {
            progressListener = l
            Log.i(TAG, "setProgressListener: ${if (l != null) "registered" else "cleared"}")
        }

        override fun getManifest(): String {
            // Built in Kotlin (not a string resource) — ONE representation of
            // the tool list; the host parses it with jsonDecode.
            return """
                {
                  "name": "Internet Search",
                  "version": "1.12.0",
                  "tools": [
                    {
                      "name": "browser_fetch",
                      "description": "Fetches a web page's text content anonymously when the page needs no browser; content that needs a browser renders in a visible tab (truncated).",
                      "parameters": {
                        "url": {
                          "type": "string",
                          "description": "The absolute http(s) URL to fetch."
                        }
                      }
                    },
                    {
                      "name": "web_search",
                      "description": "Searches the web in a visible tab and returns the text content of the top results (up to 3 pages fetched in parallel, truncated).",
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
            beginInvoke()
            val startedAtMs = SystemClock.elapsedRealtime()
            progress("round_start")
            try {
                val reply = when (toolName) {
                    "browser_fetch" -> invokeBrowserFetch(paramsJson)
                    "web_search" -> invokeWebSearch(paramsJson)
                    else -> err("Unknown tool: $toolName")
                }
                val ok = runCatching {
                    JSONObject(reply).optBoolean("ok", false)
                }.getOrDefault(false)
                progress(
                    "round_done",
                    JSONObject()
                        .put("tool", toolName)
                        .put("ok", ok)
                        .put("ms", SystemClock.elapsedRealtime() - startedAtMs),
                )
                return reply
            } finally {
                endInvoke()
            }
        }

        /**
         * Presentation switch only (AIDL `presentUi`) — names this plugin's
         * tab UI so the HOST can bring it to the front. The host still owns
         * the `invoke` that pulls data; this decides which window is on
         * screen. Not a tool: never in the manifest.
         *
         * Does NOT call startActivity() here: this service is a bound
         * background process with no visible window, and Android 10+ blocks
         * the launch (BAL_BLOCK — verified on Lenovo TB138FC 2026-10-08).
         * The host is the visible app and performs the startActivity.
         *
         * Same-process: the service and MainActivity share [TabHolder], so
         * the activity shows the tabs the in-flight `web_search` is already
         * opening (or has just opened).
         */
        override fun presentUi(): String {
            val component = ComponentName(
                this@InternetPluginService,
                MainActivity::class.java,
            ).flattenToString()
            Log.i(TAG, "presentUi: naming plugin UI $component (host launches it)")
            return JSONObject().put("ok", true).put("component", component).toString()
        }
    }

    // ------------------------------------------------------------------ //
    // Shared visible-tab plumbing                                         //
    // ------------------------------------------------------------------ //

    /** The visible-tab extract engine. Created lazily — its main-thread
     *  handler is cheap, but keep it one per service. The ctor hook is the
     *  fetcher's own progress feed (page_loaded / settle_wait / settle_done /
     *  render_retry / extracted / render_timeout / load_failed /
     *  render_blank), wired into the SAME [progress] push the service's own
     *  emissions use (plan: verbose-web-search-bubble). */
    private val pageFetcher by lazy {
        WebViewPageFetcher(this) { code, params -> progress(code, params) }
    }

    /** Create a tab WebView on the main thread (Android requires it). */
    private fun newTabWebView(): WebView = pageFetcher.onMain {
        pageFetcher.createTabWebView()
    }

    /** The tab's live WebView, or null when the bookkeeping lost it. */
    private fun tabWebView(tab: TabManager.Tab<Any>): WebView? = tab.webView as? WebView

    // ------------------------------------------------------------------ //
    // Tool: browser_fetch (one visible tab)                               //
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
        // ---- Tier 1: anonymous HTTPS GET ([WebFetchTiers], product UA) —
        // the least-powerful honest client. Skipped FOR engine URLs
        // ([TabManager.isEngineUrl] is the single source — never an OkHttp
        // against a search engine; the ToS cut is absolute). ----
        if (!WebFetchTiers.skipTier1(url)) {
            val t1 = runCatching { WebFetchTiers.fetchAnonymous(url) }.getOrNull()
            if (t1 == null) {
                Log.i(TAG, "FC: [InternetPlugin] tier=1 okhttp failed → tier=2 tab")
            } else {
                val page = HtmlTextExtractor.extract(t1.rawHtml)
                if (WebFetchTiers.decideAfterTier1(t1.status, page.text, t1.rawHtml) ==
                    WebFetchTiers.TierDecision.Ready
                ) {
                    // Thin/captcha/HTTP-error results fall through to the
                    // visible tab below; only a clean thick 200 serves here.
                    Log.i(
                        TAG,
                        "FC: [InternetPlugin] browser_fetch tier=1 okhttp ok " +
                            "(${page.text.length} chars)",
                    )
                    val outcome = FetchOutcome(t1.finalUrl, page.title, page.text, path = "anon")
                    logFetch("browser_fetch", query = null, url, outcome)
                    return searchEnvelope(
                        segments = listOf(
                            SearchSegment(
                                url, outcome.finalUrl, outcome.title, outcome.text,
                                path = outcome.path,
                            ),
                        ),
                        query = null,
                        budget = budget,
                    )
                }
                Log.i(
                    TAG,
                    "FC: [InternetPlugin] tier=1 thin/captcha " +
                        "(${page.text.length} chars, HTTP ${t1.status}) → tier=2 tab",
                )
            }
        }
        // ---- Tier 2 / 3: the VISIBLE tab (today's pipeline, unchanged). A
        // wall the tab also cannot pass becomes Tier 3 — failure IS the
        // answer, the tab stays open for the user (no auto-retry). ----
        var finalUrl = url
        return try {
            val webView = newTabWebView()
            val tab = TabHolder.manager.openUrlTab(url, url, webView)
            val content = pageFetcher.fetch(webView, url)
                .get(WebViewPageFetcher.RENDER_TIMEOUT_S, TimeUnit.SECONDS)
            // getUrl() is a WebView call — binder threads must hop to main.
            finalUrl = pageFetcher.onMain { webView.url }
                ?.takeIf { it.isNotBlank() } ?: url
            TabHolder.manager.updateChrome(tab.id, content.title, finalUrl)
            if (looksLikeCaptchaPage(content.text) || looksLikeCaptchaPage(content.html)) {
                markCaptcha("page", url, "human check on the fetched page")
                logFetchError("browser_fetch", query = null, url, "error:${humanCheckMessage("page")}")
                return err(humanCheckMessage("page"))
            }
            // Tier-2 verdict: thick serves; a thin non-captcha extract is an
            // honest "still not readable" → Tier 3 (the tab stays open at
            // [finalUrl] — the message says exactly that, and the model
            // relays it verbatim).
            if (WebFetchTiers.decideAfterTier2(content.text) == WebFetchTiers.TierDecision.NeedsHuman) {
                Log.i(
                    TAG,
                    "FC: [InternetPlugin] tier=2 tab thin (${content.text.length} chars) → tier=3",
                )
                val msg = WebFetchTiers.needsInteractionMessage(finalUrl)
                logFetchError("browser_fetch", query = null, url, "error:$msg")
                return err(msg)
            }
            val outcome = FetchOutcome(finalUrl, content.title, content.text)
            logFetch("browser_fetch", query = null, url, outcome)
            Log.i(
                TAG,
                "FC: [InternetPlugin] browser_fetch tier=2 tab served " +
                    "(${outcome.text.length} chars)",
            )
            return searchEnvelope(
                segments = listOf(
                    SearchSegment(
                        url, outcome.finalUrl, outcome.title, outcome.text,
                        path = outcome.path,
                    ),
                ),
                query = null,
                budget = budget,
            )
        } catch (e: Exception) {
            Log.w(TAG, "browser_fetch($url) failed: $e")
            val msg = e.message ?: e.javaClass.simpleName
            // The app log keeps the RAW reason (describeFetchError in
            // main.dart glosses it); the envelope the MODEL reads gets the
            // plain-language class + the search-instead coach.
            logFetchError("browser_fetch", query = null, url, "error:$msg")
            // A BLANK tab render is Tier 2 also coming back honest-empty →
            // Tier 3, the tab is already open. Any other failure (DNS, HTTP
            // codes, render timeout) keeps [describeFetchFailure]'s
            // classification — for a dead host the search-instead coach is
            // truer than "needs interaction".
            if (msg == WebViewPageFetcher.TAB_EMPTY_MSG) {
                Log.i(TAG, "FC: [InternetPlugin] tier=2 tab empty → tier=3")
                return err(WebFetchTiers.needsInteractionMessage(finalUrl))
            }
            err(describeFetchFailure(msg, url))
        }
    }

    // ------------------------------------------------------------------ //
    // Tool: web_search (SERP tab + I'm Lucky tab)                         //
    // ------------------------------------------------------------------ //

    /**
     * web_search (+73 host engine pick, S2 visible-tab retarget): the HOST
     * picks the engine — the `engine` param is injected into the tool args by
     * the host controller from a persisted user setting (google / duckduckgo /
     * qwant); the model never emits it.
     *
     * Flow (multi-gather — plan "web_search: multi-gather"):
     *  1. open a SERP tab (proof, in front) + a Lucky slot (empty, behind)
     *  2. walk the SERP tab's anchors → [pickSerpCandidates]
     *  3. TIER-1 FAN-OUT: every tier-1-eligible candidate is fetched in ONE
     *     parallel anonymous batch ([WebFetchTiers.fetchAnonymousParallel]);
     *     EVERY acceptable page is kept as a [SearchSegment] — no
     *     first-accepted-wins (that bug let candidate #1's player chrome
     *     beat 9 unread candidates)
     *  4. TIER-2 TAB LADDER: candidates the fan-out did not cover (engine
     *     goto-tokens via [WebFetchTiers.skipTier1], and tier-1 failures)
     *     render in the Lucky tab — still filling the segment cap, gated by
     *     [SEARCH_ROUND_BUDGET_MS]; blank/error walks to the next candidate,
     *     captcha on a result is Terminal
     *  5. the kept segments go out as the multi-source envelope
     *     ([searchEnvelope] — `data.results[]`, flat fields = the primary)
     *
     * Captcha: a challenge page in the SERP tab arms [CaptchaGate] +
     * notification and returns [humanCheckMessage]. NO cookie-unlock
     * auto-retry — the human solves it in the visible tab and the model
     * searches again.
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
        val engine = parseEngineName(params.optString("engine"))
        val serpUrl = TabManager.serpUrl(query, engine)
        val startedAt = System.currentTimeMillis()
        logSearchRequest(query, serpUrl)
        return try {
            val serpWebView = newTabWebView()
            val luckyWebView = newTabWebView()
            val (serpTab, luckyTab) = TabHolder.manager.openSearchTabs(
                query = query,
                engine = engine,
                serpWebView = serpWebView,
                luckyWebView = luckyWebView,
            )
            progress(
                "tabs_opened",
                JSONObject()
                    .put("engine", engine)
                    .put("images", BrowserSettings.loadImages),
            )

            // SERP settle + anchor walk — the visible proof tab.
            val links = pageFetcher.fetchLinks(serpWebView, serpUrl)
                .get(WebViewPageFetcher.RENDER_TIMEOUT_S, TimeUnit.SECONDS)
            TabHolder.manager.updateChrome(serpTab.id, links.first, serpUrl)
            var rawUrls = links.second.split('\n').filter { it.isNotBlank() }
            var candidates = pickSerpCandidates(rawUrls, engine)
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engine]('$query') SERP walk: " +
                    "${rawUrls.size} links → ${candidates.size} usable candidate(s)",
            )
            progress(
                "serp_links",
                JSONObject()
                    .put("links", rawUrls.size)
                    .put("candidates", candidates.size),
            )

            if (candidates.isEmpty()) {
                // The first walk can settle on engine chrome while the organic
                // results are still hydrating (link list stable, page text not).
                // Re-walk the ALREADY-LOADED DOM before giving up — same
                // visible page, later in its life.
                val rewalk = runCatching {
                    pageFetcher.extractLinksLoaded(serpWebView)
                        .get(5, TimeUnit.SECONDS)
                }.getOrNull()
                if (rewalk != null) {
                    val retryUrls = rewalk.second.split('\n').filter { it.isNotBlank() }
                    val retry = pickSerpCandidates(retryUrls, engine)
                    Log.i(
                        TAG,
                        "FC: [InternetPlugin] SERP re-walk: " +
                            "${retryUrls.size} links → ${retry.size} usable candidate(s)",
                    )
                    if (retry.isNotEmpty()) {
                        rawUrls = retryUrls
                        candidates = retry
                        progress(
                            "serp_rewalk",
                            JSONObject()
                                .put("links", retryUrls.size)
                                .put("candidates", retry.size),
                        )
                    }
                }
            }

            if (candidates.isEmpty()) {
                // Distinguish "no results" from "the engine wants a human".
                val probe = runCatching {
                    pageFetcher.extractLoaded(serpWebView)
                        .get(5, TimeUnit.SECONDS)
                }.getOrNull()
                // Host + path snippet — enough to see chrome vs a redirector
                // that failed to unwrap, without dumping full result URLs.
                val sample = rawUrls.take(8).map { raw -> sampleHref(raw) }
                Log.i(
                    TAG,
                    "FC: [InternetPlugin] SERP walk empty — sample: ${sample.joinToString()}",
                )
                progress("serp_empty", JSONObject().put("links", rawUrls.size))
                val challenge = probe != null &&
                    (looksLikeCaptchaPage(probe.text) || looksLikeCaptchaPage(probe.html))
                if (challenge) {
                    markCaptcha(engine, serpUrl, "challenge page served in place of results")
                    logFetchError(
                        "web_search", query = query, serpUrl,
                        "error:${humanCheckMessage(engine)}",
                    )
                    return err(humanCheckMessage(engine))
                }
                logFetchError(
                    "web_search", query = query, serpUrl,
                    "error:no-usable-result (${rawUrls.size} links walked)",
                )
                return err(
                    "$engine returned no results that the in-app browser can " +
                        "open for this query.",
                )
            }

            val cap = MAX_SEARCH_CANDIDATES.coerceAtLeast(0)
            val capped = candidates.take(cap)

            // ---- Step 1: tier-1 fan-out (multi-gather). Every tier-1-
            // eligible candidate is fetched in ONE parallel anonymous batch —
            // wall clock ≈ one T1 call, not N — and EVERY acceptable page is
            // KEPT (no first-accepted-wins). The skipTier1 filter here is the
            // one engine-knowledge point; the fan-out itself takes bare URLs.
            // With the redirector carve-out this is naturally every capped
            // candidate (≤3) — the user's 3 imageless parallel OkHttp reads.
            val t1Eligible = capped.filter { !WebFetchTiers.skipTier1(it) }
            // Per-worker events fire INSIDE the workers at their real start /
            // completion times (real completion timing — not the wait-all
            // drain). The lambda maps the worker's URL back to its SERP rank
            // (the fan-out's index is the FILTERED list's; the bubble shows
            // "candidate N/M" on the SERP order) and extracts the SHORT host
            // label — the tiers client stays free of engine/URL text
            // processing. 1-based i (t2_open's `n` is 1-based too).
            fun candidateRank(url: String): Int =
                capped.indexOf(url).takeIf { it >= 0 }?.plus(1) ?: 0
            fun tier1Event(code: String, url: String, block: JSONObject.() -> Unit) {
                progress(
                    code,
                    JSONObject()
                        .put("i", candidateRank(url))
                        .put("n", capped.size)
                        .put("host", hostOf(url) ?: "?")
                        .apply(block),
                )
            }
            val t1Outcomes = if (t1Eligible.isEmpty()) {
                emptyMap()
            } else {
                WebFetchTiers.fetchAnonymousParallel(
                    t1Eligible,
                    onWorkerStarted = { _, url ->
                        tier1Event("t1_start", url) {}
                    },
                    onWorkerResult = { _, url, res, ms ->
                        val page = res?.let { HtmlTextExtractor.extract(it.rawHtml) }
                        val verdict = when {
                            res == null -> "transport"
                            WebFetchTiers.decideAfterTier1(
                                res.status, page?.text.orEmpty(), res.rawHtml,
                            ) == WebFetchTiers.TierDecision.Ready -> "ready"
                            else -> "escalate"
                        }
                        tier1Event("t1_result", url) {
                            put("ok", res != null)
                            put("status", res?.status ?: 0)
                            put("chars", page?.text?.length ?: 0)
                            put("ms", ms)
                            put("verdict", verdict)
                        }
                    },
                )
            }
            val segments = selectTier1Segments(capped, t1Outcomes).toMutableList()
            for (segment in segments) {
                logFetch(
                    "web_search", query = query, segment.requestUrl,
                    FetchOutcome(segment.finalUrl, segment.title, segment.text, path = segment.path),
                )
            }
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engine]('$query') multi-gather: " +
                    "${capped.size} candidate(s), ${t1Eligible.size} tier-1 tried → " +
                    "${segments.size} segment(s) kept",
            )
            progress(
                "t1_done",
                JSONObject()
                    .put("tried", t1Eligible.size)
                    .put("kept", segments.size),
            )
            val t1Covered = segments.map { it.requestUrl }.toSet()

            // ---- Step 2: tier-2 tab ladder (unified fallback rule). Every
            // candidate the fan-out did NOT already cover — tab-worthy
            // skipTier1 URLs (Google's /goto top result) and tier-1 failures
            // — renders in the Lucky tab, while segments remain under the
            // cap. This reproduces today's T1-fail→tab escalation on the
            // same candidate, and keeps the goto-pick capability.
            var terminal: String? = null
            if (segments.size < cap) {
                for ((idx, candidate) in capped.withIndex()) {
                    if (candidate in t1Covered) continue
                    // Budget applies to EVERY start (incl. isLast) — a
                    // candidate opened past the wall clock cannot finish
                    // before the host's invoke timeout and turns a real
                    // result into "timed out". The fan-out ran BEFORE this
                    // gate; the pinned render tail is
                    // SEARCH_ROUND_BUDGET_MS + RENDER_TIMEOUT_S*1000 ≤ 44 s.
                    if (pastRoundBudget(startedAt, System.currentTimeMillis())) {
                        Log.i(
                            TAG,
                            "FC: [InternetPlugin] web_search[$engine]('$query') hit the " +
                                "round budget after $idx candidate(s) — stopping the walk",
                        )
                        progress("budget_stop", JSONObject().put("after", idx))
                        break
                    }
                    // The last candidate's never-silently-drop semantics
                    // apply only while the search has produced NOTHING: a
                    // thin last render is still accepted and a failure
                    // becomes the error envelope. With segments already
                    // kept, a dead last candidate just ends the walk — the
                    // kept pages serve.
                    val isLast = segments.isEmpty() && idx == capped.lastIndex
                    progress(
                        "t2_open",
                        JSONObject()
                            .put("n", idx + 1)
                            .put("of", capped.size),
                    )
                    when (val outcome = tryLuckyCandidate(
                        luckyTab = luckyTab,
                        luckyWebView = luckyWebView,
                        candidate = candidate,
                        query = query,
                        engine = engine,
                        budget = budget,
                        isLast = isLast,
                    )) {
                        is LuckyOutcome.Success -> {
                            segments.add(outcome.segment)
                            if (segments.size >= cap) break
                        }
                        is LuckyOutcome.TryNext -> Unit
                        is LuckyOutcome.Terminal -> {
                            terminal = outcome.envelope
                            break
                        }
                    }
                }
            }
            if (terminal != null) return terminal

            if (segments.isEmpty()) {
                logFetchError(
                    "web_search", query = query, serpUrl,
                    "error:all-results-failed ($engine, ${capped.size} tried)",
                )
                progress("all_failed", JSONObject().put("tried", capped.size))
                // Every candidate failed — put the SERP (the proof) back in
                // front instead of leaving a broken result tab as the active
                // surface.
                TabHolder.manager.setActive(serpTab.id)
                return err(
                    "Search failed: every result $engine returned failed to load " +
                        "(${capped.size} tried).",
                )
            }

            // Tab-surface bookkeeping (both today's behaviors preserved): a
            // tier-2 win leaves the Lucky tab open and in front (WYSIWYG —
            // the user watched it render); a PURE tier-1 win closes the
            // never-navigated Lucky slot and puts the SERP (the proof) back.
            if (segments.none { it.path == "tab" }) {
                TabHolder.manager.close(luckyTab.id)
                TabHolder.manager.setActive(serpTab.id)
            }
            progress("compose", JSONObject().put("segments", segments.size))
            return searchEnvelope(segments, query, budget)
        } catch (e: Exception) {
            Log.w(TAG, "web_search[$engine]($query) failed: $e")
            val msg = e.message ?: e.javaClass.simpleName
            logFetchError("web_search", query = query, serpUrl, "error:$msg")
            err("Search failed: $msg")
        }
    }

    /**
     * The tier-2 tab ladder's per-candidate verdict (multi-gather): Success
     * keeps a segment, TryNext moves to the next SERP link, Terminal stops
     * the WHOLE walk and serves [envelope] as-is (a captcha coach — never
     * auto-retry — or the last-candidate error envelope when the search has
     * produced nothing: failure IS the answer).
     */
    private sealed interface LuckyOutcome {
        data class Success(val segment: SearchSegment) : LuckyOutcome
        object TryNext : LuckyOutcome
        data class Terminal(val envelope: String) : LuckyOutcome
    }

    /**
     * Navigate the I'm Lucky tab to [candidate] and extract its text — the
     * TIER-2 fallback of the multi-gather ladder. Returns [LuckyOutcome.Success]
     * (with the kept segment — the tab stays open and in front, WYSIWYG)
     * when the page rendered something the model can use; TryNext when the
     * walk should try the next SERP link (blank render, or a load/extract
     * failure while more candidates remain); Terminal on a captcha (coach,
     * stop everything) or — only when [isLast] and nothing is kept yet — a
     * failure envelope (never silently drop the only thing the search
     * returned).
     */
    private fun tryLuckyCandidate(
        luckyTab: TabManager.Tab<Any>,
        luckyWebView: WebView,
        candidate: String,
        query: String,
        engine: String,
        budget: Int,
        isLast: Boolean,
    ): LuckyOutcome {
        return try {
            TabHolder.manager.navigate(luckyTab.id, candidate, candidate)
            // Focus follows content — the result tab is empty until now, so
            // the SERP stayed in front (2026-10-07 empty-Lucky regression).
            TabHolder.manager.setActive(luckyTab.id)
            val content = pageFetcher.fetch(luckyWebView, candidate)
                .get(WebViewPageFetcher.RENDER_TIMEOUT_S, TimeUnit.SECONDS)
            // getUrl() is a WebView call — binder threads must hop to main.
            // A bare `luckyWebView.url` here threw AFTER a successful extract
            // ("A WebView method was called on thread 'binder:…'") and walked
            // past a usable result (2026-10-07 AccuWeather 19°C case).
            val finalUrl = pageFetcher.onMain { luckyWebView.url }
                ?.takeIf { it.isNotBlank() } ?: candidate
            TabHolder.manager.updateChrome(luckyTab.id, content.title, finalUrl)
            if (looksLikeCaptchaPage(content.text) || looksLikeCaptchaPage(content.html)) {
                markCaptcha(engine, candidate, "human check on the opened result")
                logFetchError(
                    "web_search", query, candidate,
                    "error:${humanCheckMessage(engine)}",
                )
                // Do NOT auto-retry after a solve — surface the coach and stop.
                return LuckyOutcome.Terminal(err(humanCheckMessage(engine)))
            }
            if (!acceptCandidateExtract(content.text, isLast)) {
                Log.i(
                    TAG,
                    "FC: [InternetPlugin] web_search[$engine]('$query') → $candidate " +
                        "rendered blank — trying next result",
                )
                logFetchError("web_search", query, candidate, "blank-render")
                progress("t2_blank")
                return LuckyOutcome.TryNext
            }
            logFetch(
                "web_search", query = query, candidate,
                FetchOutcome(finalUrl, content.title, content.text),
            )
            progress(
                "t2_extracted",
                JSONObject()
                    .put("chars", content.text.length)
                    .put("host", hostOf(finalUrl) ?: "?"),
            )
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engine]('$query') → $finalUrl " +
                    "tier=2 tab served (${content.text.length} chars)",
            )
            LuckyOutcome.Success(
                SearchSegment(candidate, finalUrl, content.title, content.text, path = "tab"),
            )
        } catch (e: Exception) {
            Log.i(
                TAG,
                "FC: [InternetPlugin] web_search[$engine]('$query') → $candidate " +
                    "failed (${e.message ?: e.javaClass.simpleName})" +
                    if (isLast) "" else " — trying next result",
            )
            progress("t2_failed")
            logFetchError(
                "web_search", query, candidate,
                "error:${e.message ?: e.javaClass.simpleName}",
            )
            if (isLast) {
                val msg = e.message ?: e.javaClass.simpleName
                LuckyOutcome.Terminal(err(describeFetchFailure(msg, candidate)))
            } else {
                LuckyOutcome.TryNext
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Fetch log + envelopes                                               //
    // ------------------------------------------------------------------ //

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
     *  (success and failure) with error reasons, so users can see exactly
     *  what the plugin requested and what happened. */
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

    /** Appends a SEARCH-REQUEST record — the engine round-trip itself (the
     *  SERP tab's URL carrying the encoded query). The plugin UI shows
     *  searches too, not only the result pages opened afterwards. status
     *  "search" renders as a neutral (non-error) row in the log. */
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

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    /** Arms [CaptchaGate] and posts the check-needed notification. NO cookie
     *  re-arm, NO auto-resume: the human solves the check in the visible tab
     *  and the model retries on the coach copy. The solve URL is the blocked
     *  URL's SITE ORIGIN ([solveUrlForHuman]) — the tab must show a page a
     *  human can act on. */
    private fun markCaptcha(engine: String, url: String, reason: String) {
        val now = System.currentTimeMillis()
        val solveUrl = solveUrlForHuman(url)
        CaptchaGate.set(engine, solveUrl, reason, now)
        CaptchaNotifications.notify(this, engine, solveUrl, now)
        Log.w(TAG, "FC: [InternetPlugin] human check needed for $engine ($reason) — gate armed")
        progress("captcha", JSONObject().put("engine", engine))
    }

    override fun onDestroy() {
        pageFetcher.close()
        super.onDestroy()
    }
}
