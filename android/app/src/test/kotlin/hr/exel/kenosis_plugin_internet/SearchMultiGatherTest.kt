package hr.exel.kenosis_plugin_internet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for web_search multi-gather (plan "web_search: multi-gather — serve
 * 2–3 pages instead of first-accepted-wins").
 *
 * The bug re-derived from first principles: the old candidate loop was
 * FIRST-ACCEPTED-WINS — `acceptCandidateExtract` accepted any non-blank
 * text, so candidate #1 (a /goto token → a YouTube lyric-VIDEO page) served
 * 1266 chars of player chrome and the model answered with zero lyrics while
 * 9 usable candidates went unread. The fix: fetch up to
 * [InternetPluginService.MAX_SEARCH_CANDIDATES] candidates (tier 1 in ONE
 * parallel batch), KEEP every acceptable segment, and serve them all.
 *
 * Everything here is pure — hand-built [WebFetchTiers.Tier1Response] maps,
 * no network, no WebView, no Android stub (org.json is the real test
 * artifact). The walk's SIDE-EFFECTING halves (captcha → Terminal stops
 * everything; a tab win leaves the Lucky tab in front; a pure tier-1 win
 * closes the never-navigated Lucky slot) are verified on-device (Phase E):
 * they are WebView/tab-manager orchestration, not logic a JVM test can pin.
 */
class SearchMultiGatherTest {

    /** ≥600 chars of clean text → Ready per [WebFetchTiers.decideAfterTier1]. */
    private val THICK = "word ".repeat(300).trim()

    private fun thickHtml(title: String, body: String = THICK): String =
        "<html><head><title>$title</title></head><body><p>$body</p></body></html>"

    private fun ready(
        url: String,
        finalUrl: String = url,
        title: String = "t-$url",
    ): WebFetchTiers.Tier1Response =
        WebFetchTiers.Tier1Response(200, thickHtml(title), finalUrl)

    private fun segmentAt(segments: List<InternetPluginService.SearchSegment>, i: Int) =
        segments[i]

    // ---------------- selectTier1Segments ----------------

    @Test
    fun `three ready candidates keep ALL three segments in engine order`() {
        // The core anti-bug pin: first-accepted-wins kept only #1; multi-
        // gather keeps every acceptable page, in ENGINE order (no re-ranking).
        val candidates = listOf(
            "https://a.example.com/one",
            "https://b.example.com/two",
            "https://c.example.com/three",
        )
        val outcomes = mapOf(
            candidates[0] to ready(candidates[0], title = "first"),
            candidates[1] to ready(candidates[1], title = "second"),
            candidates[2] to ready(candidates[2], title = "third"),
        )
        val segments = InternetPluginService.selectTier1Segments(candidates, outcomes)
        assertEquals(3, segments.size)
        assertEquals(listOf("first", "second", "third"), segments.map { it.title })
        // requestUrl = the SERP's candidate; finalUrl = post-redirect landing.
        assertEquals(candidates, segments.map { it.requestUrl })
        assertEquals(candidates, segments.map { it.finalUrl })
        assertEquals(listOf("anon", "anon", "anon"), segments.map { it.path })
    }

    @Test
    fun `a FAILED (null) candidate yields nothing but does not stop assembly`() {
        // The starvation shape: if #1 dies, #2 and #3 must still serve.
        val candidates = listOf(
            "https://dead.example.com/x",
            "https://b.example.com/two",
            "https://c.example.com/three",
        )
        val outcomes = mapOf(
            candidates[0] to null, // transport failure inside the fan-out
            candidates[1] to ready(candidates[1], title = "second"),
            candidates[2] to ready(candidates[2], title = "third"),
        )
        val segments = InternetPluginService.selectTier1Segments(candidates, outcomes)
        assertEquals(listOf("second", "third"), segments.map { it.title })
    }

    @Test
    fun `a NOT-READY (thin) candidate yields nothing but does not stop assembly`() {
        val candidates = listOf(
            "https://thin.example.com/x",
            "https://b.example.com/two",
        )
        val outcomes = mapOf(
            // 200 but a JS-shell-thin extract → EscalateToTab → no segment.
            candidates[0] to WebFetchTiers.Tier1Response(
                200,
                "<html><body>loading…</body></html>",
                candidates[0],
            ),
            candidates[1] to ready(candidates[1], title = "second"),
        )
        val segments = InternetPluginService.selectTier1Segments(candidates, outcomes)
        assertEquals(listOf("second"), segments.map { it.title })
    }

    @Test
    fun `an ABSENT outcome yields nothing but does not stop assembly`() {
        // A candidate missing from the map entirely (defensive — the fan-out
        // contract guarantees completeness, but a gap must never stop the
        // assembly of the rest).
        val candidates = listOf("https://missing.example.com/x", "https://b.example.com/two")
        val outcomes = mapOf(candidates[1] to ready(candidates[1], title = "second"))
        val segments = InternetPluginService.selectTier1Segments(candidates, outcomes)
        assertEquals(listOf("second"), segments.map { it.title })
    }

    @Test
    fun `duplicate final urls collapse — the better-ranked candidate wins`() {
        // Two SERP candidates redirecting to the SAME page: engine rank wins
        // the slot, the loser never duplicates a segment.
        val candidates = listOf(
            "https://rank1.example.com/a",
            "https://rank2.example.com/b",
        )
        val outcomes = mapOf(
            candidates[0] to ready(candidates[0], finalUrl = "https://real.example.com/page"),
            candidates[1] to ready(candidates[1], finalUrl = "https://real.example.com/page"),
        )
        val segments = InternetPluginService.selectTier1Segments(candidates, outcomes)
        assertEquals(1, segments.size)
        assertEquals("https://rank1.example.com/a", segmentAt(segments, 0).requestUrl)
    }

    // ---------------- the fan-out eligibility split (engine URLs) ----------------

    @Test
    fun `goto tokens and content URLs ALL hit the fan-out — redirector carve-out`() {
        // Plan: verbose-web-search-bubble fast path (explicit relaxation,
        // 2026-10-12): Google /goto?url=CAES-… is an OUTBOUND RESULT POINTER.
        // The fan-out OkHttp-follows its 302 with the product UA and reads
        // the target CONTENT page — a goto candidate is Tier-1-eligible like
        // any plain candidate: all ≤3 capped candidates fetch CONCURRENTLY
        // (the user's 3 imageless parallel reads), so the eligibility split
        // here covers EVERYTHING and the tier-2 ladder is fallback-only.
        // A captcha/interstitial target is just a null/thin outcome → the
        // tab ladder, exactly the same route as a failed plain candidate.
        val goto = "https://www.google.com/goto?url=CAESaBEAAAEAjm8x"
        val candidates = listOf(goto, "https://b.example.com/two", "https://c.example.com/three")

        // Exactly what invokeWebSearch computes:
        val capped = candidates.take(InternetPluginService.MAX_SEARCH_CANDIDATES)
        val t1Eligible = capped.filter { !WebFetchTiers.skipTier1(it) }
        // The carve-out: still an engine URL (TabManager), but T1-eligible.
        assertTrue(TabManager.isEngineUrl(goto))
        assertFalse(WebFetchTiers.skipTier1(goto))
        assertEquals(candidates, t1Eligible)

        // Fan-out covers EVERY candidate (bare URLs, no engine token):
        val outcomes = mapOf(
            t1Eligible[0] to ready(t1Eligible[0], title = "goto-target-page"),
            t1Eligible[1] to ready(t1Eligible[1], title = "second"),
            t1Eligible[2] to ready(t1Eligible[2], title = "third"),
        )
        val segments = InternetPluginService.selectTier1Segments(capped, outcomes)
        assertEquals(3, segments.size)
        // Engine rank preserved — the goto token first (SERP rank #1).
        assertEquals(goto, segmentAt(segments, 0).requestUrl)
        // Nothing left for the tab ladder.
        val covered = segments.map { it.requestUrl }.toSet()
        assertTrue(capped.none { it !in covered })
    }

    @Test
    fun `engine CONTENT urls still skip the fan-out — ladder keeps them`() {
        // The carve-out's boundary: a redirector is a POINTER; the engine's
        // own surfaces stay WebView-only (SERP page, accounts, nav, CDN).
        for (url in listOf(
            "https://www.google.com/search?q=test&udm=14",
            "https://accounts.google.com/ServiceLogin",
            "https://www.qwant.com/?q=test",
            "https://html.duckduckgo.com/html/?q=test",
        )) {
            assertTrue(url, WebFetchTiers.skipTier1(url))
        }
        val candidates = listOf(
            "https://b.example.com/two",
            "https://www.google.com/search?q=x",
        )
        val capped = candidates.take(InternetPluginService.MAX_SEARCH_CANDIDATES)
        val t1Eligible = capped.filter { !WebFetchTiers.skipTier1(it) }
        assertEquals(listOf(candidates[0]), t1Eligible)
    }

    @Test
    fun `fan-out progress hooks fire per worker with real timings`() {
        // Plan: verbose-web-search-bubble — the step feed's t1_start/t1_result
        // events come from inside the workers (real completion timing, not
        // the wait-all drain), receiving bare values (index/url/outcome/elapsed)
        // with NO host/label parsing in the tier client (engine-token ban).
        val starts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val results = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val outcomes = WebFetchTiers.fetchAnonymousParallel(
            listOf("not a url", ":::"), // both unparseable → transport failures → null
            onWorkerStarted = { i, _ -> starts.add(i) },
            onWorkerResult = { i, _, _, elMs -> results.add(i); check(elMs >= 0) },
        )
        // Workers are CONCURRENT — their start/finish call order is
        // scheduler luck; only the payload per worker index is pinned.
        assertEquals(setOf(0, 1), starts.toSet())
        assertEquals(setOf(0, 1), results.toSet())
        // Complete map even when every fetch throws.
        assertEquals(setOf("not a url", ":::"), outcomes.keys)
        assertNull(outcomes["not a url"])
        assertNull(outcomes[":::"])
    }

    @Test
    fun `progressPayload shape is exactly seq-code-tMs-params`() {
        // The AIDL IKenosisProgressListener.onProgress payload, pinned (the
        // Dart PluginProgressEvent.tryFromJson parses this shape).
        val payload = JSONObject(
            InternetPluginService.progressPayload(
                seq = 7,
                code = "t1_result",
                tMs = 12_345,
                params = JSONObject().put("i", 2).put("n", 3),
            ),
        )
        assertEquals(7, payload.getLong("seq"))
        assertEquals("t1_result", payload.getString("code"))
        assertEquals(12_345, payload.getLong("tMs"))
        assertEquals(2, payload.getJSONObject("params").getInt("i"))
        assertEquals(3, payload.getJSONObject("params").getInt("n"))
        assertEquals(4, payload.length())
    }

    // ---------------- searchEnvelope ----------------

    @Test
    fun `envelope primary mirrors the first result and carries every segment`() {
        val segments = listOf(
            InternetPluginService.SearchSegment(
                "https://a.example.com/one", "https://a.example.com/one",
                "first", "text-one", path = "anon",
            ),
            InternetPluginService.SearchSegment(
                "https://b.example.com/two", "https://b.example.com/two",
                "second", "text-two", path = "tab",
            ),
        )
        val json = JSONObject(InternetPluginService.searchEnvelope(segments, "q", 6144))
        assertEquals(true, json.getBoolean("ok"))
        val data = json.getJSONObject("data")
        // Old-host compat: flat fields = the primary = results[0].
        assertEquals("https://a.example.com/one", data.getString("url"))
        assertEquals("first", data.getString("title"))
        assertEquals("text-one", data.getString("text"))
        assertEquals("q", data.getString("query"))
        val results = data.getJSONArray("results")
        assertEquals(2, results.length())
        assertEquals("first", results.getJSONObject(0).getString("title"))
        assertEquals("text-one", results.getJSONObject(0).getString("text"))
        assertEquals("second", results.getJSONObject(1).getString("title"))
        assertEquals("text-two", results.getJSONObject(1).getString("text"))
        // Results entries carry url/title/text only — the path vocabulary
        // stays internal (the host composes the observation from the text).
        for (i in 0 until results.length()) {
            assertFalse(results.getJSONObject(i).has("path"))
        }
    }

    @Test
    fun `envelope truncates EACH segment at the budget`() {
        // budget is the PER-SEGMENT ceiling: 3 segments × budget chars max —
        // the host applies its own compose-time budget across sources.
        val long = "x".repeat(10_000)
        val segments = (1..3).map { i ->
            InternetPluginService.SearchSegment(
                "https://e$i.example.com/", "https://e$i.example.com/", "t$i", long, path = "anon",
            )
        }
        val json = JSONObject(InternetPluginService.searchEnvelope(segments, null, 1_000))
        val results = json.getJSONObject("data").getJSONArray("results")
        for (i in 0 until results.length()) {
            assertEquals(1_000, results.getJSONObject(i).getString("text").length)
        }
        assertEquals(1_000, json.getJSONObject("data").getString("text").length)
    }

    @Test
    fun `one segment is the old flat shape plus a 1-entry results array`() {
        // browser_fetch (and a 1-hit search) must stay byte-compatible with
        // the old okEnvelope shape: ok/data{url,title,text[,query]} — the
        // only addition is the results array.
        val segment = InternetPluginService.SearchSegment(
            "https://req.example.com/", "https://final.example.com/", "title", "body", path = "anon",
        )
        val data = JSONObject(
            InternetPluginService.searchEnvelope(listOf(segment), null, 6144),
        ).getJSONObject("data")
        assertEquals("https://final.example.com/", data.getString("url"))
        assertEquals("title", data.getString("title"))
        assertEquals("body", data.getString("text"))
        assertEquals(1, data.getJSONArray("results").length())
        assertFalse(data.has("query"))
    }

    @Test
    fun `empty segments give an ok envelope with empty flat fields`() {
        // Defensive: the caller never serves an empty segment list (it
        // returns error:all-results-failed instead), but the pure helper
        // must not crash on one.
        val data = JSONObject(
            InternetPluginService.searchEnvelope(emptyList(), null, 6144),
        ).getJSONObject("data")
        assertEquals("", data.getString("url"))
        assertEquals("", data.getString("text"))
        assertEquals(0, data.getJSONArray("results").length())
    }

    // ---------------- the tab-ladder round-budget gate ----------------

    @Test
    fun `round-budget gate admits a render AT the boundary, breaks past it`() {
        val start = 1_000_000L
        val budget = InternetPluginService.SEARCH_ROUND_BUDGET_MS
        // At the wall exactly: still admit (a render started here can finish
        // — this is what keeps a slow SERP + fan-out from killing the ladder).
        assertFalse(InternetPluginService.pastRoundBudget(start, start + budget))
        // One ms past: break.
        assertTrue(InternetPluginService.pastRoundBudget(start, start + budget + 1))
    }

    // ---------------- caps and predicates shared with today ----------------

    @Test
    fun `segment cap is 3 — the fan-out cap AND the walk cap`() {
        assertEquals(3, InternetPluginService.MAX_SEARCH_CANDIDATES)
    }

    @Test
    fun `tier-2 acceptance predicate is byte-identical to first-accepted-wins era`() {
        // The TAB fallback still accepts any non-blank render mid-list and
        // always accepts the last candidate — multi-gather changed HOW MANY
        // pages serve, not the acceptance predicate.
        assertTrue(InternetPluginService.acceptCandidateExtract("content", isLast = false))
        assertFalse(InternetPluginService.acceptCandidateExtract("", isLast = false))
        assertTrue(InternetPluginService.acceptCandidateExtract("", isLast = true))
    }
}
