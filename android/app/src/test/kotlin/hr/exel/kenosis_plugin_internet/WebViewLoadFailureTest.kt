package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for [WebViewPageFetcher.loadFailureReason] — the gate that
 * stops the extractor from scraping the WebView's built-in ERROR page and
 * serving it as the search observation.
 *
 * +73 on-device regression this pins: Google IFL picked a Facebook post as
 * the top result → Facebook redirected the render to a `fb://native_post/…`
 * deep link → the WebView fired onPageFinished with the fb:// URL and its
 * "Web page not available … net::ERR_UNKNOWN_URL_SCHEME" screen → the
 * extractor served that 212-char error text to the model, which told the
 * user the search "result is an error and does not contain any actual
 * information". A finished load whose page is an error screen must FAIL.
 */
class WebViewLoadFailureTest {

    // ---------------- pages that ARE content (null = extractable) ----------------

    @Test
    fun `https page with no main-frame error is extractable`() {
        assertNull(
            WebViewPageFetcher.loadFailureReason(
                "https://www.index.hr/mobile",
                null,
            ),
        )
    }

    @Test
    fun `http page is extractable and scheme case is ignored`() {
        assertNull(
            WebViewPageFetcher.loadFailureReason("HTTP://example.com/page", null),
        )
    }

    // ---------------- the on-device regression: non-web-scheme redirect ----------------

    @Test
    fun `fb deep-link redirect (the +73 on-device case) fails`() {
        val reason = WebViewPageFetcher.loadFailureReason(
            "fb://native_post/UzpfSTEwMDA2NDg0MzM4NDkzODoxNTIzODUwNDU2NDUzMDUzOjE1MjM4NTA0NTY0NTMwNTM=?wtsid=wt_0bZFwxleygPjAFlc1",
            null,
        )
        assertTrue(reason != null)
        assertTrue(
            "reason should say the link is non-web: $reason",
            reason!!.contains("non-web link")
        )
    }

    @Test
    fun `intent and about blank schemes fail too`() {
        assertTrue(
            WebViewPageFetcher.loadFailureReason("intent://post/123#Intent", null) != null,
        )
        assertTrue(
            WebViewPageFetcher.loadFailureReason("about:blank", null) != null,
        )
    }

    // ---------------- main-frame load errors ----------------

    @Test
    fun `main-frame network error fails with the error in the reason`() {
        val reason = WebViewPageFetcher.loadFailureReason(
            "https://dead.example.com/",
            "net::ERR_NAME_NOT_RESOLVED",
        )
        assertTrue(reason != null)
        assertTrue(
            "reason should carry the captured error: $reason",
            reason!!.contains("net::ERR_NAME_NOT_RESOLVED")
        )
    }

    @Test
    fun `main-frame http error status fails`() {
        val reason = WebViewPageFetcher.loadFailureReason(
            "https://example.com/gone",
            "HTTP 404",
        )
        assertTrue(reason != null)
        assertTrue(
            "reason should carry the status: $reason",
            reason!!.contains("HTTP 404")
        )
    }

    @Test
    fun `the error wins over a bad url when both are present`() {
        // Order matters: the captured main-frame error is the more specific
        // signal; the url check is the backstop for cases where Chromium
        // reports the scheme failure only via the final onPageFinished url.
        val reason = WebViewPageFetcher.loadFailureReason(
            "fb://native_post/123",
            "net::ERR_UNKNOWN_URL_SCHEME",
        )
        assertTrue(reason != null)
        assertTrue(
            "error takes precedence over the url check: $reason",
            reason!!.contains("net::ERR_UNKNOWN_URL_SCHEME")
        )
    }

    // ---------------- degenerate inputs ----------------

    @Test
    fun `null page url with no error fails`() {
        assertTrue(WebViewPageFetcher.loadFailureReason(null, null) != null)
    }
}

/**
 * Boundary tests for [WebViewPageFetcher.errorPageReason] — the CONTENT-level
 * backstop behind [WebViewPageFetcher.loadFailureReason].
 *
 * 2026-10-03 on-device regression this pins: a share-URL analyse saved a
 * library document titled "Web page not available" (a 717-byte .txt) — the
 * WebView's own error page was extracted and returned as a SUCCESSFUL fetch
 * even though [loadFailureReason] shipped in that build (the error page
 * rendered on a navigation where no main-frame error callback was captured).
 * An extract carrying the error page's deterministic title/body signature
 * must FAIL, never serve.
 */
class WebViewErrorPageReasonTest {

    // ---------------- content (null = extractable) ----------------

    @Test
    fun `a real page title and body are extractable`() {
        assertNull(
            WebViewPageFetcher.errorPageReason(
                "Yr - Zagreb - Long term forecast",
                "Skip to content A collaboration between NRK and The Norwegian " +
                    "Meteorological Institute Zagreb Capital, City of Zagreb " +
                    "(Croatia), elevation 138 m …",
            ),
        )
    }

    @Test
    fun `a long article quoting net err early is still extractable`() {
        // Over the 600-char scope on purpose: the body signature only refuses
        // SHORT extracts (error pages are tiny); a long article that leads
        // with a quoted net:: code is real content.
        assertNull(
            WebViewPageFetcher.errorPageReason(
                "Debugging Android WebView loads",
                "The most common failure is net::ERR_NAME_NOT_RESOLVED, which " +
                    "means DNS died. This article walks through fifteen more " +
                    "codes and how to read the Chrome://net-export trace you " +
                    "captured alongside the logcat output from the device. " +
                    "We start with DNS provisioning, move through captive " +
                    "portal detection, then look at the WebView client " +
                    "callbacks in the order the framework fires them, and " +
                    "finish with the rendering pipeline: first paint, the " +
                    "AJAX settle window, and the extraction deadline that " +
                    "bounds how long a blank challenge shell may keep the " +
                    "fetcher waiting before the attempt is abandoned. With " +
                    "those mechanics pinned down, every signature this " +
                    "guard could ever mistake for an error page becomes " +
                    "obviously content instead.",
            ),
        )
    }

    // ---------------- title signatures ----------------

    @Test
    fun `legacy chromium title fails (the 2026-10-03 on-device spelling)`() {
        val reason = WebViewPageFetcher.errorPageReason(
            "Web page not available",
            "Web page not available net::ERR_ADDRESS_UNREACHABLE",
        )
        assertTrue(reason != null)
        assertTrue(
            "reason should say the browser served its error page: $reason",
            reason!!.contains("error page"),
        )
    }

    @Test
    fun `current chromium title fails too`() {
        assertTrue(
            WebViewPageFetcher.errorPageReason(
                "Webpage not available",
                "The webpage at https://dead.example.com/ could not be loaded",
            ) != null,
        )
    }

    @Test
    fun `title match is case-insensitive and trims whitespace`() {
        assertTrue(
            WebViewPageFetcher.errorPageReason(
                "  WEB PAGE NOT AVAILABLE ",
                "some body text",
            ) != null,
        )
    }

    // ---------------- body signature (title extraction missed) ----------------

    @Test
    fun `short body naming net err fails when the title is not canonical`() {
        val reason = WebViewPageFetcher.errorPageReason(
            "about:blank",
            "net::ERR_NAME_NOT_RESOLVED",
        )
        assertTrue(reason != null)
        assertTrue(
            "reason should name the net:: error page: $reason",
            reason!!.contains("net:: error"),
        )
    }

    @Test
    fun `a long document naming net err in the body head is extractable`() {
        // The length scope: error pages are tiny; a LONG extract that happens
        // to lead with a quoted net:: code is real content.
        assertNull(
            WebViewPageFetcher.errorPageReason(
                "Connectivity troubleshooting log",
                "net::ERR_TIMED_OUT was the first of 40 failures recorded in " +
                    "this long field report. " + "Detail line. ".repeat(120),
            ),
        )
    }
}