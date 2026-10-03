package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for [WebViewPageFetcher.shouldRetryExtraction] — the gate
 * behind the empty-render poll loop.
 *
 * +73 on-device case this pins: Qwant's DataDome challenge shell renders
 * script-only (0-char innerText) at the first onPageFinished; the real SERP
 * payload only appears after the challenge JS reloads the page. A blank
 * render BEFORE the deadline must retry (the payload may still land);
 * non-blank text is content (complete), and a blank render past the deadline
 * never becomes content (fail with the honest reason instead of polling
 * into the watchdog).
 */
class RenderRetryPolicyTest {

    @Test
    fun `blank render before the deadline retries`() {
        assertTrue(
            WebViewPageFetcher.shouldRetryExtraction(
                "",
                nowMs = 1000L,
                deadlineMs = 2000L,
            )
        )
        assertTrue(
            WebViewPageFetcher.shouldRetryExtraction(
                "   \n\t  ",
                nowMs = 1999L,
                deadlineMs = 2000L,
            )
        )
    }

    @Test
    fun `first non-blank render polls once to confirm stability`() {
        // 2026-10-03 on-device (yr.no daily-table): the first extract was
        // non-blank but PARTIAL — chrome + current conditions rendered, the
        // data table hydrated from a later XHR. A first extract carries no
        // stability evidence — it retries so the next poll can confirm.
        assertTrue(
            WebViewPageFetcher.shouldRetryExtraction(
                "Wind forecast for Split: 12 kn",
                nowMs = 1000L,
                deadlineMs = 2000L,
            )
        )
    }

    @Test
    fun `unchanged consecutive extracts are settled - no retry`() {
        // Two identical extracts = the DOM stopped filling — complete.
        assertFalse(
            WebViewPageFetcher.shouldRetryExtraction(
                "Wind forecast for Split: 12 kn",
                nowMs = 1000L,
                deadlineMs = 2000L,
                previousText = "Wind forecast for Split: 12 kn",
            )
        )
    }

    @Test
    fun `changed extract vs previous poll retries - hydration in progress`() {
        // The text GREW/changed between polls — the page is still hydrating
        // (the yr.no table landing between polls). Keep polling.
        assertTrue(
            WebViewPageFetcher.shouldRetryExtraction(
                "Zagreb forecast: today 18°C. Saturday 16, Sunday 14.",
                nowMs = 1000L,
                deadlineMs = 2000L,
                previousText = "Zagreb forecast: today 18°C.",
            )
        )
    }

    @Test
    fun `non-blank unstable extract at the deadline stops retrying`() {
        // Deadline reached with content still changing — the CALLER completes
        // with the latest text (partial real content beats failing); the
        // gate itself must stop the loop.
        assertFalse(
            WebViewPageFetcher.shouldRetryExtraction(
                "Zagreb forecast: today 18°C. Saturday 16.",
                nowMs = 2000L,
                deadlineMs = 2000L,
                previousText = "Zagreb forecast: today 18°C.",
            )
        )
    }

    @Test
    fun `blank render at or past the deadline does not retry`() {
        // Deadline reached: a still-blank page never becomes content —
        // fail with "no readable content", don't poll into the watchdog.
        assertFalse(
            WebViewPageFetcher.shouldRetryExtraction(
                "",
                nowMs = 2000L,
                deadlineMs = 2000L,
            )
        )
        assertFalse(
            WebViewPageFetcher.shouldRetryExtraction(
                "",
                nowMs = 5000L,
                deadlineMs = 2000L,
            )
        )
    }
}