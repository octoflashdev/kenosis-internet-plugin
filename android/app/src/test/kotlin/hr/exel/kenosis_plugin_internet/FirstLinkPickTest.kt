package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SERP candidate pick that backs the visible "I'm Lucky"
 * tab: [InternetPluginService.resolveResultTarget],
 * [InternetPluginService.pickSerpCandidates],
 * [InternetPluginService.pickGoogleResults],
 * [InternetPluginService.acceptCandidateExtract].
 *
 * Contract (the ToS cut): the model opens the 1st OPENABLE SERP link in a
 * visible tab and reads only what that tab displays. The pick is pure URL
 * hygiene — unwrap engine redirectors, drop the engine's own chrome, drop
 * app-gated hosts, https only, keep order so a blank render can fall back
 * to the 2nd / 3rd candidate.
 *
 * Pure JVM (java.net.URI only — no Android, no HTML client).
 */
class FirstLinkPickTest {

    // ---------------- resolveResultTarget ----------------

    @Test
    fun `ddg l redirect unwraps uddg target`() {
        assertEquals(
            "https://example.com/page",
            InternetPluginService.resolveResultTarget(
                "https://html.duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=abc",
            ),
        )
    }

    @Test
    fun `google url interstitial unwraps q target`() {
        assertEquals(
            "https://example.com/page",
            InternetPluginService.resolveResultTarget(
                "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage&sa=D",
            ),
        )
    }

    @Test
    fun `qwant redirect unwraps url target`() {
        assertEquals(
            "https://example.com/page",
            InternetPluginService.resolveResultTarget(
                "https://www.qwant.com/redirect?url=https%3A%2F%2Fexample.com%2Fpage",
            ),
        )
    }

    @Test
    fun `plain result url is left alone`() {
        assertEquals(
            "https://example.com/page",
            InternetPluginService.resolveResultTarget("https://example.com/page"),
        )
    }

    @Test
    fun `engine page with a random q param is NOT unwrapped`() {
        // Only the known redirector PATHS unwrap — a result URL that merely
        // carries `?q=` must stay whole.
        val raw = "https://example.com/search?q=https%3A%2F%2Fevil.com"
        assertEquals(raw, InternetPluginService.resolveResultTarget(raw))
    }

    // ---------------- google goto?url=CAES (2026-10-07 CDP) ----------------

    @Test
    fun `google goto encrypted token stays whole - navigable result pointer`() {
        // Organic results are `/goto?url=CAES-…` (Google's encrypted blob).
        // There is NO plaintext target in href / ping / data-*, so the pick
        // keeps the goto URL and the Lucky tab navigates (Google 302s).
        val goto = "https://www.google.com/goto?url=CAES-wUB6zswFcumpUt4Wfg-OaqfZ124"
        assertEquals(goto, InternetPluginService.resolveResultTarget(goto))
    }

    @Test
    fun `google goto with a plaintext url target unwraps`() {
        assertEquals(
            "https://example.com/page",
            InternetPluginService.resolveResultTarget(
                "https://www.google.com/goto?url=https%3A%2F%2Fexample.com%2Fpage",
            ),
        )
    }

    @Test
    fun `isEngineRedirector recognizes goto url l and redirect shapes`() {
        assertTrue(
            InternetPluginService.isEngineRedirector(
                "https://www.google.com/goto?url=CAES-x",
            ),
        )
        assertTrue(
            InternetPluginService.isEngineRedirector(
                "https://www.google.com/url?q=https%3A%2F%2Fx.example",
            ),
        )
        assertTrue(
            InternetPluginService.isEngineRedirector(
                "https://html.duckduckgo.com/l/?uddg=https%3A%2F%2Fx.example",
            ),
        )
        assertTrue(
            InternetPluginService.isEngineRedirector(
                "https://www.qwant.com/redirect?url=https%3A%2F%2Fx.example",
            ),
        )
        assertFalse(
            InternetPluginService.isEngineRedirector("https://www.google.com/search?q=x"),
        )
        assertFalse(
            InternetPluginService.isEngineRedirector("https://example.com/goto?url=x"),
        )
    }

    @Test
    fun `isNavigableResultRedirector is true only for google goto with a target`() {
        assertTrue(
            InternetPluginService.isNavigableResultRedirector(
                "https://www.google.com/goto?url=CAES-wUB6zsw",
            ),
        )
        // Un-unwrappable garbage is NOT navigable — it stays engine-own.
        assertFalse(
            InternetPluginService.isNavigableResultRedirector(
                "https://html.duckduckgo.com/l/?uddg=not-a-url",
            ),
        )
        assertFalse(
            InternetPluginService.isNavigableResultRedirector(
                "https://www.google.com/url?q=garbage",
            ),
        )
        assertFalse(
            InternetPluginService.isNavigableResultRedirector(
                "https://www.google.com/goto",
            ),
        )
    }

    @Test
    fun `pickGoogleResults keeps goto results and drops google chrome`() {
        // The real 2026-10-07 SERP shape: chrome hrefs lead the walk, the
        // organic results are `/goto?url=CAES-…`. The pick must return the
        // gotos (navigable) and drop the chrome.
        val chrome = listOf(
            "https://www.google.com/?sa=X",
            "https://accounts.google.com/ServiceLogin",
            "https://www.google.com/search?q=temperature+in+zagreb+now&udm=50",
            "https://www.google.com/setprefs",
            "https://www.google.com/preferences",
            "https://support.google.com/websearch",
            "https://policies.google.com/privacy",
        )
        val gotos = (1..3).map { "https://www.google.com/goto?url=CAES-result$it" }
        val picked = InternetPluginService.pickGoogleResults(chrome + gotos)
        assertEquals(gotos, picked)
    }

    @Test
    fun `rankSerpHrefs puts goto results before google chrome`() {
        val ranked = InternetPluginService.rankSerpHrefs(
            listOf(
                "https://www.google.com/search?q=next",
                "https://www.google.com/goto?url=CAES-first",
                "https://www.google.com/goto?url=CAES-second",
            ),
            "google",
        )
        assertEquals("https://www.google.com/goto?url=CAES-first", ranked[0])
        assertEquals("https://www.google.com/goto?url=CAES-second", ranked[1])
    }

    // ---------------- pickGoogleResults (1st openable link) ----------------

    @Test
    fun `pickGoogleResults returns the first openable https result`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "https://www.google.com/search/about",
                "https://example.com/first",
                "https://example.com/second",
            ),
        )
        assertEquals("https://example.com/first", picked.first())
    }

    @Test
    fun `pickGoogleResults skips app-gated hosts and falls back to the next`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "https://www.facebook.com/story.php",
                "https://m.facebook.com/notes",
                "https://example.com/real",
            ),
        )
        assertEquals(listOf("https://example.com/real"), picked)
    }

    @Test
    fun `pickGoogleResults keeps 2nd and 3rd as fallback candidates`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "https://example.com/one",
                "https://example.com/two",
                "https://example.com/three",
                "https://example.com/four",
            ),
        )
        // The Lucky tab tries 1 → 2 → 3 (MAX_SEARCH_CANDIDATES); the pick
        // must preserve order so that fallback has somewhere to go.
        assertEquals("https://example.com/one", picked[0])
        assertEquals("https://example.com/two", picked[1])
        assertEquals("https://example.com/three", picked[2])
    }

    @Test
    fun `pickGoogleResults drops http-only and keeps https`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "http://example.com/insecure",
                "https://example.com/secure",
            ),
        )
        assertEquals(listOf("https://example.com/secure"), picked)
    }

    @Test
    fun `pickGoogleResults unwraps google redirect before filtering`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage&sa=D",
            ),
        )
        assertEquals(listOf("https://example.com/page"), picked)
    }

    @Test
    fun `pickGoogleResults drops google's own chrome and ad hosts`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "https://www.google.com/preferences",
                "https://googleads.g.doubleclick.net/pagead/id",
                "https://example.com/page",
            ),
        )
        assertEquals(listOf("https://example.com/page"), picked)
    }

    @Test
    fun `pickGoogleResults dedupes while preserving order`() {
        val picked = InternetPluginService.pickGoogleResults(
            listOf(
                "https://example.com/a",
                "https://example.com/a",
                "https://example.com/b",
            ),
        )
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), picked)
    }

    @Test
    fun `pickGoogleResults caps at 10 usable candidates`() {
        val raw = (1..20).map { "https://example.com/p$it" }
        assertEquals(10, InternetPluginService.pickGoogleResults(raw).size)
    }

    @Test
    fun `pickGoogleResult is pickGoogleResults first`() {
        assertEquals(
            "https://example.com/first",
            InternetPluginService.pickGoogleResult(
                listOf("https://example.com/first", "https://example.com/second"),
            ),
        )
    }

    // ---------------- pickSerpCandidates engine scoping ----------------

    @Test
    fun `pickSerpCandidates drops duckduckgo chrome for the ddg engine`() {
        val picked = InternetPluginService.pickSerpCandidates(
            listOf(
                "https://duckduckgo.com/y.js?ad_domain=example",
                "https://html.duckduckgo.com/html/?q=next",
                "https://example.com/page",
            ),
            "duckduckgo",
        )
        assertEquals(listOf("https://example.com/page"), picked)
    }

    @Test
    fun `pickSerpCandidates drops qwant chrome for the qwant engine`() {
        val picked = InternetPluginService.pickSerpCandidates(
            listOf(
                "https://www.qwant.com/?q=nav",
                "https://lite.qwant.com/?q=nav",
                "https://example.com/page",
            ),
            "qwant",
        )
        assertEquals(listOf("https://example.com/page"), picked)
    }

    @Test
    fun `pickSerpCandidates returns empty when every candidate is filtered`() {
        val picked = InternetPluginService.pickSerpCandidates(
            listOf(
                "https://www.facebook.com/x",
                "https://www.google.com/about",
                "not-a-url",
                "ftp://example.com/x",
            ),
            "google",
        )
        assertTrue(picked.isEmpty())
    }

    // ---------------- rankSerpHrefs (chrome-heavy SERP) ----------------

    @Test
    fun `rankSerpHrefs puts off-site results before same-site chrome`() {
        val ranked = InternetPluginService.rankSerpHrefs(
            listOf(
                "https://www.google.com/search?q=next",
                "https://www.google.com/preferences",
                "https://example.com/result",
                "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fwrapped",
            ),
            "google",
        )
        assertEquals("https://example.com/result", ranked[0])
        assertEquals(
            "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fwrapped",
            ranked[1],
        )
        assertTrue(ranked.indexOf("https://www.google.com/preferences") >= 2)
    }

    @Test
    fun `pickSerpCandidates still finds the result when chrome leads the walk`() {
        // 2026-10-07 on-device regression: mobile Google's first 40 hrefs
        // were nav/footer chrome → `40 links → 0 usable candidate(s)`.
        val chrome = (1..40).map { "https://www.google.com/nav$it" }
        val picked = InternetPluginService.pickSerpCandidates(
            chrome + listOf(
                "https://www.accuweather.com/en/hr/zagreb/123",
                "https://www.timeanddate.com/weather/croatia/zagreb",
            ),
            "google",
        )
        assertEquals(
            listOf(
                "https://www.accuweather.com/en/hr/zagreb/123",
                "https://www.timeanddate.com/weather/croatia/zagreb",
            ),
            picked,
        )
    }

    @Test
    fun `pickSerpCandidates drops google subdomain chrome before the real result`() {
        // 2026-10-07 on-device (Lenovo): `45 links → 0 usable` — the walk's
        // first 40 were accounts/support/policies/gstatic/www.google chrome.
        // Subdomains are still the engine family; only the organic host counts.
        val chrome = listOf(
            "https://accounts.google.com/ServiceLogin",
            "https://support.google.com/websearch",
            "https://policies.google.com/privacy",
            "https://www.gstatic.com/images/branding/searchlogo.png",
        )
        val picked = InternetPluginService.pickSerpCandidates(
            chrome + listOf("https://example.com/weather/zagreb"),
            "google",
        )
        assertEquals(listOf("https://example.com/weather/zagreb"), picked)
    }

    @Test
    fun `sampleHref shows host and path for a walk-empty diagnostic`() {
        assertEquals(
            "accounts.google.com/ServiceLogin?continue=x",
            InternetPluginService.sampleHref("https://accounts.google.com/ServiceLogin?continue=x"),
        )
        assertEquals("example.com/page", InternetPluginService.sampleHref("https://example.com/page"))
    }

    // ---------------- acceptCandidateExtract ----------------

    @Test
    fun `blank mid-list extract is rejected so the next candidate opens`() {
        assertFalse(InternetPluginService.acceptCandidateExtract("", isLast = false))
        assertFalse(InternetPluginService.acceptCandidateExtract("   \n", isLast = false))
    }

    @Test
    fun `non-blank mid-list extract is accepted (honest WYSIWYG)`() {
        assertTrue(InternetPluginService.acceptCandidateExtract("short page", isLast = false))
    }

    @Test
    fun `last candidate is always accepted`() {
        assertTrue(InternetPluginService.acceptCandidateExtract("", isLast = true))
    }
}
