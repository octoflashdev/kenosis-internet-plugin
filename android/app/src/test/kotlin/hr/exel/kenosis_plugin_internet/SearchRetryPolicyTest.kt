package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for [InternetPluginService.acceptSearchResult] — the pure
 * policy a web_search candidate walk uses to decide whether a fetched
 * result's text is ACCEPTED (returned to the model) or SKIPPED to try the
 * next result.
 *
 * The policy: accept readable text ([InternetPluginService.shouldFallback]
 * false) always; accept thin text ONLY on the last candidate (a short real
 * page beats an error envelope when there is nothing else); skip thin text
 * when more candidates remain. This is the seam that lets the retry loop
 * walk the SERP candidate list past a result that rendered blank / bot-block
 * / 404 — the regression where the FIRST unreadable result failed the whole
 * search.
 *
 * Pure — no device, no network. [InternetPluginService.MIN_USEFUL_CHARS] = 200.
 */
class SearchRetryPolicyTest {

    // ---------------- readable text (>= MIN_USEFUL_CHARS) ----------------

    @Test
    fun `readable text is accepted regardless of isLast`() {
        val readable = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS)
        assertTrue(InternetPluginService.acceptSearchResult(readable, isLast = false))
        assertTrue(InternetPluginService.acceptSearchResult(readable, isLast = true))
    }

    @Test
    fun `text at exactly the min threshold is readable (strictly-less thin check)`() {
        val atThreshold = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS)
        // shouldFallback is `< MIN_USEFUL_CHARS`, so exactly MIN is NOT thin.
        assertTrue(InternetPluginService.acceptSearchResult(atThreshold, isLast = false))
    }

    @Test
    fun `long real page is accepted`() {
        val page = "The excavation at Göbekli Tepe revealed ".repeat(50)
        assertTrue(page.length > InternetPluginService.MIN_USEFUL_CHARS)
        assertTrue(InternetPluginService.acceptSearchResult(page, isLast = false))
        assertTrue(InternetPluginService.acceptSearchResult(page, isLast = true))
    }

    // ---------------- thin text (< MIN_USEFUL_CHARS) ----------------

    @Test
    fun `thin text is skipped when more candidates remain`() {
        val thin = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS - 1)
        assertFalse(InternetPluginService.acceptSearchResult(thin, isLast = false))
    }

    @Test
    fun `thin text is accepted on the last candidate`() {
        // A short real page beats an error envelope when there is nothing
        // else to try — never discard the only thing the search returned.
        val thin = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS - 1)
        assertTrue(InternetPluginService.acceptSearchResult(thin, isLast = true))
    }

    @Test
    fun `blank render is thin`() {
        assertFalse(InternetPluginService.acceptSearchResult("", isLast = false))
        assertFalse(InternetPluginService.acceptSearchResult("   \n\t ", isLast = false))
    }

    @Test
    fun `blank render on the last candidate is accepted rather than nothing`() {
        // Even a blank last result is returned: the alternative is an error
        // envelope, and the per-candidate fetch log already recorded why each
        // earlier candidate failed — the model gets the (thin) page text.
        assertTrue(InternetPluginService.acceptSearchResult("", isLast = true))
    }

    @Test
    fun `js-shell boilerplate is thin and skipped`() {
        // The classic JS-shell shape: a `<div id="root"></div>` SPA that the
        // WebView also couldn't hydrate. Shorter than MIN_USEFUL_CHARS even
        // after trim.
        val shell = "Enable JavaScript to run this app."
        assertTrue(shell.length < InternetPluginService.MIN_USEFUL_CHARS)
        assertFalse(InternetPluginService.acceptSearchResult(shell, isLast = false))
    }

    // ---------------- the isLast trade-off boundary ----------------

    @Test
    fun `the isLast flag flips thin from skip to accept`() {
        val thin = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS - 1)
        // Same text, different position in the candidate list.
        assertFalse(InternetPluginService.acceptSearchResult(thin, isLast = false))
        assertTrue(InternetPluginService.acceptSearchResult(thin, isLast = true))
    }

    @Test
    fun `isLast never makes readable text rejected`() {
        val readable = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS + 50)
        assertTrue(InternetPluginService.acceptSearchResult(readable, isLast = true))
    }
}