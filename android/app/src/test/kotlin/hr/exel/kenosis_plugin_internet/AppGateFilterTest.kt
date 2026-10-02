package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for the candidate FILTERS introduced by the +73 on-device
 * fix for "google and qwant don't work anymore":
 *
 *  * [InternetPluginService.isAppGatedUrl] — hosts whose pages deep-link a
 *    headless WebView into an app scheme (fb://…) and can never render. The
 *    google IFL resolver picked a facebook.com post for a live query and the
 *    load died with ERR_UNKNOWN_URL_SCHEME — those hosts are now skipped
 *    BEFORE wasting a fetch.
 *  * [InternetPluginService.pickGoogleResult] — the first USABLE link from
 *    a google SERP anchor walk (own-domain links and app-gated hosts out,
 *    https only, order kept).
 *
 * Pure functions — no device, no network.
 */
class AppGateFilterTest {

    // ---------------- isAppGatedUrl ----------------

    @Test
    fun `app-gated hosts are recognized`() {
        assertTrue(InternetPluginService.isAppGatedUrl("https://facebook.com/post/123"))
        assertTrue(InternetPluginService.isAppGatedUrl("https://m.facebook.com/story.php?id=1"))
        assertTrue(InternetPluginService.isAppGatedUrl("https://www.instagram.com/p/abc/"))
        assertTrue(InternetPluginService.isAppGatedUrl("https://www.tiktok.com/@user/video/1"))
        assertTrue(InternetPluginService.isAppGatedUrl("https://twitter.com/user/status/1"))
        assertTrue(InternetPluginService.isAppGatedUrl("https://x.com/user/status/1"))
        assertTrue(InternetPluginService.isAppGatedUrl("https://m.x.com/home"))
    }

    @Test
    fun `non-gated hosts pass through`() {
        assertFalse(InternetPluginService.isAppGatedUrl("https://iflscience.com/the-object"))
        assertFalse(InternetPluginService.isAppGatedUrl("https://en.wikipedia.org/wiki/Split"))
        assertFalse(InternetPluginService.isAppGatedUrl("http://example.com/"))
    }

    @Test
    fun `host must be an exact or subdomain match, not a suffix lookalike`() {
        // ".facebook.com" suffix must match a SUBDOMAIN, not a lookalike
        // second-level domain or a path/query fragment.
        assertFalse(InternetPluginService.isAppGatedUrl("https://facebook.example.com/"))
        assertFalse(InternetPluginService.isAppGatedUrl("https://not-facebook.com/"))
        assertFalse(InternetPluginService.isAppGatedUrl("https://example.com/facebook.com/path"))
        assertFalse(InternetPluginService.isAppGatedUrl("https://example.com/?ref=x.com"))
    }

    @Test
    fun `unparseable garbage is not app-gated`() {
        // The scheme check rejects it downstream; the filter stays honest.
        assertFalse(InternetPluginService.isAppGatedUrl("not a url"))
        assertFalse(InternetPluginService.isAppGatedUrl(""))
    }

    // ---------------- pickGoogleResult ----------------

    @Test
    fun `google own-domain and ad links are dropped`() {
        assertNull(
            InternetPluginService.pickGoogleResult(
                listOf(
                    "https://www.google.com/search?q=next+page",
                    "https://accounts.google.com/ServiceLogin",
                    "https://www.google.de/",
                    "https://ad.doubleclick.net/clk/123",
                ),
            ),
        )
    }

    @Test
    fun `app-gated and non-https candidates are dropped`() {
        assertNull(
            InternetPluginService.pickGoogleResult(
                listOf(
                    "https://m.facebook.com/post/1",
                    "http://iflscience.com/insecure",
                ),
            ),
        )
    }

    @Test
    fun `first usable https result wins with order kept`() {
        val pick = InternetPluginService.pickGoogleResult(
            listOf(
                "https://www.google.com/search?q=x",
                "https://www.facebook.com/post/1",
                "https://iflscience.com/the-object",
                "https://en.wikipedia.org/wiki/Split",
            ),
        )
        assertEquals("https://iflscience.com/the-object", pick)
    }

    @Test
    fun `empty candidate list picks nothing`() {
        assertNull(InternetPluginService.pickGoogleResult(emptyList()))
    }

    // ---------------- pickGoogleResults (the full ordered list) ---------

    @Test
    fun `pickGoogleResults keeps every usable result in order`() {
        // The retry loop needs the FULL list to fall back to the next result
        // when the first can't be fetched — the single-pick [pickGoogleResult]
        // alone can't recover from a non-app-gated host that 404s.
        val picks = InternetPluginService.pickGoogleResults(
            listOf(
                "https://www.google.com/search?q=x",
                "https://www.facebook.com/post/1",
                "https://iflscience.com/the-object",
                "https://en.wikipedia.org/wiki/Split",
                "http://insecure.example.com/",
            ),
        )
        assertEquals(
            listOf("https://iflscience.com/the-object", "https://en.wikipedia.org/wiki/Split"),
            picks,
        )
    }

    @Test
    fun `pickGoogleResults empty when every candidate is filtered`() {
        assertTrue(
            InternetPluginService.pickGoogleResults(
                listOf(
                    "https://www.google.com/search?q=next",
                    "https://accounts.google.com/ServiceLogin",
                    "https://www.facebook.com/post/1",
                    "http://insecure.example.com/",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `pickGoogleResults preserves SERP order, does not re-rank`() {
        val picks = InternetPluginService.pickGoogleResults(
            listOf(
                "https://b.example.com/2",
                "https://a.example.com/1",
                "https://c.example.com/3",
            ),
        )
        assertEquals(
            listOf("https://b.example.com/2", "https://a.example.com/1", "https://c.example.com/3"),
            picks,
        )
    }

    @Test
    fun `pickGoogleResult delegates to pickGoogleResults firstOrNull`() {
        // The single-pick alias must agree with the plural's first element.
        val urls = listOf(
            "https://www.google.com/search?q=x",
            "https://iflscience.com/the-object",
            "https://en.wikipedia.org/wiki/Split",
        )
        assertEquals(
            InternetPluginService.pickGoogleResults(urls).firstOrNull(),
            InternetPluginService.pickGoogleResult(urls),
        )
    }

    // ---------------- real WebView UA (WebViewPageFetcher) ----------------

    @Test
    fun `real webview ua strips the wv token`() {
        // The "; wv)" token is how sites detect and bot-block WebViews.
        val realUa = "Mozilla/5.0 (Linux; Android 16; SM-S928B; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.71 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; SM-S928B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.71 Mobile Safari/537.36",
            WebViewPageFetcher.realWebviewUa(realUa),
        )
    }

    @Test
    fun `ua without the wv token passes through unchanged`() {
        val plainUa = "Mozilla/5.0 (Linux; Android 16; SM-S928B) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.71 Mobile Safari/537.36"
        assertEquals(plainUa, WebViewPageFetcher.realWebviewUa(plainUa))
    }

    @Test
    fun `null or blank ua falls back to the chrome-mobile constant`() {
        val fallback = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        assertEquals(fallback, WebViewPageFetcher.realWebviewUa(null))
        assertEquals(fallback, WebViewPageFetcher.realWebviewUa(""))
        assertEquals(fallback, WebViewPageFetcher.realWebviewUa("   "))
        // Wholesale-strip to empty must also fall back, never a blank UA.
        assertEquals(fallback, WebViewPageFetcher.realWebviewUa("; wv)"))
    }

    @Test
    fun `fallback ua itself carries no wv token`() {
        // The fallback must never re-announce itself as a WebView.
        assertTrue(WebViewPageFetcher.realWebviewUa(null).isNotBlank())
        assertFalse(WebViewPageFetcher.realWebviewUa(null).contains("wv)"))
        assertFalse(WebViewPageFetcher.realWebviewUa(null).startsWith(" "))
    }
}