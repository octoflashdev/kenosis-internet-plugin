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