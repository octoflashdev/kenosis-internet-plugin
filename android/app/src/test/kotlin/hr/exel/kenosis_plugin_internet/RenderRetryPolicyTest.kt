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
    fun `non-blank render does not retry`() {
        // Content is content — never poll past it.
        assertFalse(
            WebViewPageFetcher.shouldRetryExtraction(
                "Wind forecast for Split: 12 kn",
                nowMs = 1000L,
                deadlineMs = 2000L,
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