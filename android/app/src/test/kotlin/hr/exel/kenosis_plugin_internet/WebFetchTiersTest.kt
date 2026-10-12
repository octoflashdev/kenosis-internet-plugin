package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the tier ladder (plan: anonymous-first-tiered-web-fetch).
 *
 * Everything here is pure — no OkHttp call, no WebView, no Android type on a
 * test path. The pins cover:
 *  - the escalation predicate table for Tier-1 and Tier-2 outcomes,
 *  - engine URLs never reach Tier 1 ([TabManager.isEngineUrl] routing),
 *  - the budget math (T1 + round budget + render ≤ 44 s < host 45 s),
 *  - the honest product UA (never a browser UA — no spoofing),
 *  - the Tier-3 message text, pinned VERBATIM (not just the error shape) —
 *    it must carry the same actionable tail as humanCheckMessage.
 */
class WebFetchTiersTest {

    private val THICK = "word ".repeat(300).trim() // 1799 chars
    private val THIN = "short page"

    // ---------------- constants ----------------

    @Test
    fun `T1 timeout is 5 s (fast path)`() {
        assertEquals(5_000L, WebFetchTiers.T1_TIMEOUT_MS)
    }

    @Test
    fun `min readable chars is 600 — the one thin gate, not per-site knowledge`() {
        assertEquals(600, WebFetchTiers.MIN_READABLE_CHARS)
    }

    @Test
    fun `UA is the truthful product UA, never a browser UA`() {
        assertEquals("KenosisAI-web/1.0 (Android)", WebFetchTiers.USER_AGENT)
        // A browser-shaped UA is identity spoofing — the one thing the tier
        // ladder must never do. The product UA carries no browser marker.
        // The UA-strip marker is ban-checked in tests too (the sanity guard
        // scans production OR tests) — build it by concatenation so this pin
        // can still assert its absence.
        val wvStrip = ";" + " wv)"
        for (marker in listOf("Mozilla", "Chrome", "Android ", "like Gecko", "Safari", wvStrip)) {
            assertFalse("spoof marker '$marker' in UA", marker in WebFetchTiers.USER_AGENT)
        }
    }

    @Test
    fun `T1 timeout is 2 s connect + 3 s read, capped by a 5 s callTimeout`() {
        // The routing contract: a timed-out T1 is an honest failure → T2.
        // Fast path (plan: verbose-web-search-bubble): connect 2 + read 3
        // stays inside the 5 s callTimeout.
        assertEquals(5_000L, WebFetchTiers.T1_TIMEOUT_MS)
        assertTrue(WebFetchTiers.T1_TIMEOUT_MS <= 5_000L)
    }

    // ---------------- budget math (the f618b27 class, checked up front) ----------------

    @Test
    fun `tier ladder worst case fits the host 45 s invoke timeout`() {
        // Multi-gather re-derivation: the tier-1 fan-out runs BEFORE the
        // round gate (its wait-all ≈ one T1 timeout is paid on top of the
        // SERP fetch), so the honest pin on the render tail is
        //   SEARCH_ROUND_BUDGET_MS + RENDER_TIMEOUT_S*1000
        // ≤ 44_000 (< the host's 45_000 PluginBridge timeout), or a future
        // timeout bump fails THIS gate instead of on the device. (The old
        // T1 + budget + render sum double-counted T1: it now runs before
        // the gate, not inside it.)
        val worstCase = InternetPluginService.SEARCH_ROUND_BUDGET_MS +
            WebViewPageFetcher.RENDER_TIMEOUT_S * 1000
        assertTrue(
            "tier ladder worst case $worstCase ms must be ≤ 44_000 ms " +
                "(host invoke timeout is 45_000 ms)",
            worstCase <= 44_000L,
        )
    }

    @Test
    fun `round budget was re-cut for multi-gather`() {
        // 28_000, not the old 20_000: after a slow SERP (15 s) + the fan-out
        // (8 s) elapsed is 23 s — a 20 s budget would block the tier-2 tab
        // ladder ENTIRELY (a regression vs first-accepted-wins).
        assertEquals(28_000L, InternetPluginService.SEARCH_ROUND_BUDGET_MS)
    }

    @Test
    fun `fan-out wait-all is T1 timeout plus the 1 s scheduling margin`() {
        assertEquals(
            WebFetchTiers.T1_TIMEOUT_MS + 1_000L,
            WebFetchTiers.T1_TIMEOUT_MS + WebFetchTiers.T1_FANOUT_WAIT_MARGIN_MS,
        )
        // The fan-out must never outwait the T1 cap by a meaningful amount —
        // it is the wall clock the round pays BEFORE the budget gate.
        assertTrue(WebFetchTiers.T1_FANOUT_WAIT_MARGIN_MS <= 1_000L)
    }

    @Test
    fun `controllable fast-path ceiling is 7100 ms — the 10 s network-phase pin`() {
        // Plan: verbose-web-search-bubble fast path. The plugin's own
        // controllable waits must stay ≤ 7.1 s so the whole network phase
        // fits under the user's <10 s bar (the host's detection decode and
        // the approval tap sit OUTSIDE it). Any constant bump that breaks
        // this pin must re-derive the SERP+fan-out budget first.
        val ceiling = WebViewPageFetcher.SERP_SETTLE_MS +
            WebViewPageFetcher.SERP_LINK_POLL_MS +
            WebFetchTiers.T1_TIMEOUT_MS +
            WebFetchTiers.T1_FANOUT_WAIT_MARGIN_MS
        assertTrue(
            "controllable fast-path ceiling $ceiling ms must be ≤ 7_100 ms",
            ceiling <= 7_100L,
        )
        assertEquals(600L, WebViewPageFetcher.SERP_SETTLE_MS)
        assertEquals(500L, WebViewPageFetcher.SERP_LINK_POLL_MS)
    }

    @Test
    fun `fan-out map is COMPLETE — a throwing fetch yields the key with null`() {
        // The caller routes nulls to the tier-2 ladder, so a failed fetch
        // must NEVER remove the key (an absent key would look like "not
        // tried"). An unparseable URL throws inside the worker — runCatching
        // turns it into a null outcome, and the map stays complete.
        val out = WebFetchTiers.fetchAnonymousParallel(
            listOf("https://example.com/a", "not a url", ":::"),
        )
        assertEquals(setOf("https://example.com/a", "not a url", ":::"), out.keys)
        assertNull("unparseable URL must map to null", out["not a url"])
        assertNull(out[":::"])
    }

    @Test
    fun `fan-out of an empty list is an empty map`() {
        assertTrue(WebFetchTiers.fetchAnonymousParallel(emptyList()).isEmpty())
    }

    // ---------------- engine URLs never reach Tier 1 ----------------

    @Test
    fun `engine URLs skip tier 1 — the ToS boundary`() {
        // The serpUrl hosts + the result-redirector family: straight to Tier 2.
        for (url in listOf(
            "https://www.google.com/search?q=test",
            "https://html.duckduckgo.com/html/?q=test",
            "https://www.qwant.com/?q=test&t=web",
            "https://www.google.com/search",
            "https://accounts.google.com/ServiceLogin",
            "https://consent.google.com/m?continue=x",
            "https://www.google.de/search?q=test", // national TLD
            "https://adservice.doubleclick.net/ddm/x",
            "https://fonts.gstatic.com/s/font.woff2",
        )) {
            assertTrue(url, WebFetchTiers.skipTier1(url))
            assertTrue(url, TabManager.isEngineUrl(url))
        }
    }

    @Test
    fun `goto-token redirector is T1-eligible — the carved-out outbound pointer`() {
        // Plan: verbose-web-search-bubble fast path (EXPLICIT INVARIANT
        // RELAXATION, user-approved 2026-10-12). Google serves organic
        // results as /goto?url=CAES-…; the redirector is an OUTBOUND RESULT
        // POINTER — OkHttp following its 302 with the product UA reads the
        // target CONTENT page (what plain candidate URLs point to anyway).
        // The relaxation ONLY covers redirector paths: TabManager.isEngineUrl
        // still pins the SERP-host ban, and engine CONTENT hosts
        // (accounts, nav, /search, CDN family) stay skipTier1 == true.
        // A captcha/interstitial /goto target is just a candidate failure →
        // EscalateToTab (decideAfterTier1) → the tier-2 ladder, unchanged.
        val goto = "https://www.google.com/goto?url=CAESaBEAAAEAjm8x"
        assertTrue(TabManager.isEngineUrl(goto))
        assertFalse(WebFetchTiers.skipTier1(goto))
        // The redirector family the pick/unwrap logic already knows.
        assertTrue(InternetPluginService.isNavigableResultRedirector(goto))
        assertEquals(true, InternetPluginService.isEngineRedirector(goto))
    }

    @Test
    fun `plain content URLs do not skip tier 1`() {
        for (url in listOf(
            "https://en.wikipedia.org/wiki/Weather",
            "https://example.com/article/2026/heatwave",
            "https://weather.example.net/",
            "https://subdomain.example.org/goto-page", // a path is not a redirector
        )) {
            assertFalse(url, WebFetchTiers.skipTier1(url))
            assertFalse(url, TabManager.isEngineUrl(url))
        }
    }

    @Test
    fun `engine redirector paths on weird hosts still stay engine`() {
        // Defense in depth: redirector paths on hosts the family match
        // somehow missed.
        for (url in listOf(
            "https://www2.duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com",
            "https://lite.qwant.com/redirect?q=abc",
        )) {
            assertTrue(url, TabManager.isEngineUrl(url))
        }
    }

    @Test
    fun `unparseable URLs do not crash the engine check`() {
        assertFalse(TabManager.isEngineUrl("not a url"))
        assertFalse(TabManager.isEngineUrl(":::"))
        assertFalse(WebFetchTiers.skipTier1("not a url"))
    }

    // ---------------- decideAfterTier1 — the predicate table ----------------

    @Test
    fun `2xx thick clean becomes Ready`() {
        assertEquals(
            WebFetchTiers.TierDecision.Ready,
            WebFetchTiers.decideAfterTier1(200, THICK, "<html>$THICK</html>"),
        )
        assertEquals(
            WebFetchTiers.TierDecision.Ready,
            WebFetchTiers.decideAfterTier1(299, THICK, "<html>$THICK</html>"),
        )
    }

    @Test
    fun `2xx thin escalates to the tab`() {
        assertEquals(
            WebFetchTiers.TierDecision.EscalateToTab,
            WebFetchTiers.decideAfterTier1(200, THIN, "<html><body>$THIN</body></html>"),
        )
        // Exactly-600 chars is the boundary — thick, Ready.
        val edge = "a".repeat(WebFetchTiers.MIN_READABLE_CHARS)
        assertEquals(
            WebFetchTiers.TierDecision.Ready,
            WebFetchTiers.decideAfterTier1(200, edge, "<html>$edge</html>"),
        )
        val justUnder = "a".repeat(WebFetchTiers.MIN_READABLE_CHARS - 1)
        assertEquals(
            WebFetchTiers.TierDecision.EscalateToTab,
            WebFetchTiers.decideAfterTier1(200, justUnder, "<html>$justUnder</html>"),
        )
    }

    @Test
    fun `non-2xx escalates to the tab — including walls`() {
        for (status in listOf(301, 307, 403, 404, 429, 500, 503)) {
            assertEquals(
                "HTTP $status",
                WebFetchTiers.TierDecision.EscalateToTab,
                WebFetchTiers.decideAfterTier1(status, THICK, "<html>err</html>"),
            )
        }
    }

    @Test
    fun `2xx thick WITH challenge markers escalates — never serve a wall as content`() {
        // Some walls answer 200 with >600 chars of interstitial — the T1
        // extract alone cannot be trusted when the html carries markers.
        val wall = "<html><head><script src=\"https://js.captcha-delivery.com/frac.js\"></script>" +
            "<body><div class=\"datadome\">Press &amp; Hold</div>" +
            "<p>$THICK</p></body></html>"
        assertEquals(
            WebFetchTiers.TierDecision.EscalateToTab,
            WebFetchTiers.decideAfterTier1(200, "$THICK Press & Hold", wall),
        )
        // Marker in the TEXT alone (html clean — synthetic) also escalates.
        assertEquals(
            WebFetchTiers.TierDecision.EscalateToTab,
            WebFetchTiers.decideAfterTier1(200, "$THICK verify you are a human", "<html>$THICK</html>"),
        )
    }

    @Test
    fun `challenge-marker probe stays scoped to the first 100 KB`() {
        // InternetPluginService.looksLikeCaptchaPage probes the first 100 KB
        // only — a huge clean body with a deep marker must stay Ready (a
        // full-body lowering scan would re-attach cost to every T1 fetch).
        val big = "x".repeat(100_000) + "captcha-delivery.com" + THICK
        assertFalse(InternetPluginService.looksLikeCaptchaPage(big))
    }

    @Test
    fun `blank T1 extract escalates`() {
        assertEquals(
            WebFetchTiers.TierDecision.EscalateToTab,
            WebFetchTiers.decideAfterTier1(200, "", "<html><body></body></html>"),
        )
    }

    // ---------------- decideAfterTier2 ----------------

    @Test
    fun `thick tab extract serves`() {
        assertEquals(
            WebFetchTiers.TierDecision.Ready,
            WebFetchTiers.decideAfterTier2(THICK),
        )
    }

    @Test
    fun `thin tab extract with no captcha is Tier 3 — needs interaction`() {
        assertEquals(
            WebFetchTiers.TierDecision.NeedsHuman,
            WebFetchTiers.decideAfterTier2(THIN),
        )
        assertEquals(
            WebFetchTiers.TierDecision.NeedsHuman,
            WebFetchTiers.decideAfterTier2(""),
        )
        val edge = "a".repeat(WebFetchTiers.MIN_READABLE_CHARS)
        assertEquals(WebFetchTiers.TierDecision.Ready, WebFetchTiers.decideAfterTier2(edge))
    }

    // ---------------- Tier-3 message (pinned verbatim — not just the shape) ----------------

    @Test
    fun `tier-3 message carries the same actionable tail as humanCheckMessage`() {
        val msg = WebFetchTiers.needsInteractionMessage("https://example.com/gate")
        // The humanCheckMessage contract: name what is needed, name the tab,
        // end with "then ask again" — the re-trigger is the user's NEXT
        // message, never an auto-retry.
        assertEquals(
            "The tool could not read this page — it needs interaction (a " +
                "login wall, a consent gate, or a script-only app). The tab " +
                "is open at https://example.com/gate. Open the Internet " +
                "Search plugin app, complete the action in the tab, then " +
                "ask again.",
            msg,
        )
        // The signature the host's model sees; distinct from the captcha
        // message ("human check (captcha)") so the two walls never conflate.
        assertNotEquals(
            InternetPluginService.humanCheckMessage("google"),
            msg,
        )
    }

    @Test
    fun `captcha message keeps its signature for the plugin UI`() {
        // describeFetchError (main.dart) keys on 'human check' — the T3
        // addition must not absorb or rewrite it.
        assertTrue(
            "human check" in InternetPluginService.humanCheckMessage("google"),
        )
    }
}