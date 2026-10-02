package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the +75 bot-wall detection helpers on
 * [InternetPluginService]: [InternetPluginService.looksLikeCaptchaPage],
 * [InternetPluginService.isBotWallHttpError], and
 * [InternetPluginService.humanCheckMessage].
 *
 * Why these matter: before +75 a bot-walled engine surfaced as a bare
 * "HTTP 403" or a misleading "returned no results for this query" — the user
 * was never told a check needed solving, and the challenge itself is
 * invisible (headless WebView). Detection is the trigger for all three
 * notification layers, so a false NEGATIVE (challenge read as empty results)
 * silently loses the recovery path and a false POSITIVE (real empty SERP
 * read as a challenge) arms a pointless banner.
 *
 * Pure — no device, no network.
 */
class CaptchaDetectionTest {

    // ---------------- looksLikeCaptchaPage ----------------

    @Test
    fun `duckduckgo anomaly page is detected`() {
        val anomaly = """
            <html><head><title>Something DPI is not working</title></head><body>
            <form action="/captcha" method="post">
            <p>Unfortunately, bots use DuckDuckGo too and we need to make sure
            you are not one of them.</p>
            <button type="submit">Continue</button>
            </form></body></html>
        """.trimIndent()
        assertTrue(InternetPluginService.looksLikeCaptchaPage(anomaly))
    }

    @Test
    fun `datadome challenge page is detected`() {
        val datadome = """
            <html><head><link rel="stylesheet" href="https://js.captcha-delivery.com/captcha.css">
            </head><body><div id="datadome-iframe">Press &amp; Hold to confirm you are
            a human (and not a bot).</div></body></html>
        """.trimIndent()
        assertTrue(InternetPluginService.looksLikeCaptchaPage(datadome))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertTrue(InternetPluginService.looksLikeCaptchaPage("Please VERIFY YOU ARE A HUMAN"))
    }

    @Test
    fun `a real duckduckgo serp is not a challenge`() {
        // Down-scaled shape of html.duckduckgo.com/html — result anchors with
        // the uddg redirect wrapper. No marker substrings.
        val serp = """
            <html><body>
            <div class="result"><a class="result__a"
            href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Farticle&amp;rut=abc">
            Example article</a></div>
            </body></html>
        """.trimIndent()
        assertFalse(InternetPluginService.looksLikeCaptchaPage(serp))
    }

    @Test
    fun `a real qwant api body is not a challenge`() {
        val api = """
            {"status":"success","data":{"result":{"items":{"mainline":[{"type":"web",
            "items":[{"url":"https://example.com/article","title":"Example"}]}]}}}}
        """.trimIndent()
        assertFalse(InternetPluginService.looksLikeCaptchaPage(api))
    }

    @Test
    fun `empty body is not a challenge`() {
        assertFalse(InternetPluginService.looksLikeCaptchaPage(""))
    }

    @Test
    fun `generic human-verification phrasing is detected`() {
        assertTrue(InternetPluginService.looksLikeCaptchaPage("<p>Human verification required</p>"))
    }

    // ---------------- isBotWallHttpError ----------------

    @Test
    fun `http 403 and 429 are bot-wall statuses`() {
        assertTrue(InternetPluginService.isBotWallHttpError(IllegalStateException("HTTP 403")))
        assertTrue(InternetPluginService.isBotWallHttpError(IllegalStateException("HTTP 429")))
    }

    @Test
    fun `other http statuses are not`() {
        // 400/401/500 are engine misbehavior, not a human check — arming a
        // banner for them would be a false positive.
        assertFalse(InternetPluginService.isBotWallHttpError(IllegalStateException("HTTP 400")))
        assertFalse(InternetPluginService.isBotWallHttpError(IllegalStateException("HTTP 500")))
        assertFalse(InternetPluginService.isBotWallHttpError(IllegalStateException("timeout")))
        assertFalse(InternetPluginService.isBotWallHttpError(null))
    }

    // ---------------- humanCheckMessage ----------------

    @Test
    fun `the envelope names the engine and carries the STABLE human-check signature`() {
        val msg = InternetPluginService.humanCheckMessage("qwant")
        assertTrue(msg.contains("qwant"))
        // describeFetchError (plugin UI) keys on this exact substring.
        assertTrue(msg.contains("human check"))
        assertTrue(msg.contains("Internet Search plugin app"))
    }
}
