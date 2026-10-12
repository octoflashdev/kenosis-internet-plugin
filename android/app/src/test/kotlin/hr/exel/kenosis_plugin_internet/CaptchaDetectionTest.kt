package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InternetPluginService.humanCheckMessage] (the error the
 * model gets when the page demands a human) and
 * [InternetPluginService.looksLikeCaptchaPage] (the HTML markers that turn
 * a render into a human-in-the-loop ping — never an auto-solve).
 *
 * Pure JVM.
 */
class CaptchaDetectionTest {

    // ---------------- humanCheckMessage ----------------

    @Test
    fun `humanCheckMessage is plain language, no api keys`() {
        val msg = InternetPluginService.humanCheckMessage("google")
        // Stable "human check" signature — the plugin UI's describeFetchError
        // keys on it.
        assertTrue("carries the human-check signature", msg.contains("human check"))
        assertTrue("tells the human what to do", msg.contains("complete the check"))
        assertTrue("says to open the plugin app", msg.contains("Internet Search plugin app"))
        assertFalse("never mentions an api key", msg.contains("api key"))
    }

    @Test
    fun `humanCheckMessage names the engine it embeds`() {
        val msg = InternetPluginService.humanCheckMessage("google")
        assertTrue(msg.contains("google"))
    }

    // ---------------- looksLikeCaptchaPage ----------------

    @Test
    fun `datadome host marker triggers`() {
        assertTrue(
            InternetPluginService.looksLikeCaptchaPage(
                "<iframe src='https://geo.captcha-delivery.com/captcha'></iframe>",
            ),
        )
    }

    @Test
    fun `datadome brand marker triggers`() {
        assertTrue(InternetPluginService.looksLikeCaptchaPage("<html><body>Captcha · DataDome</body></html>"))
    }

    @Test
    fun `press and hold CTA triggers`() {
        // Extracted text form (the DOM walk un-entities `&amp;` → `&`).
        assertTrue(InternetPluginService.looksLikeCaptchaPage("Press & hold to continue"))
    }

    @Test
    fun `ddg anomaly copy triggers`() {
        assertTrue(
            InternetPluginService.looksLikeCaptchaPage(
                "Unfortunately, bots use DuckDuckGo too. Please complete the check.",
            ),
        )
    }

    @Test
    fun `verify-you-are-a-human copy triggers`() {
        assertTrue(InternetPluginService.looksLikeCaptchaPage("Please verify you are a human"))
    }

    @Test
    fun `clean text does not trigger`() {
        val body = "This is a perfectly ordinary article about cats and dogs and weather and more."
        assertFalse(InternetPluginService.looksLikeCaptchaPage(body))
    }

    @Test
    fun `empty body does not trigger`() {
        assertFalse(InternetPluginService.looksLikeCaptchaPage(""))
    }
}
