package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for InternetPluginService.shouldFallback — the pure
 * fast-path→WebView-fallback trigger. MIN_USEFUL_CHARS = 200 (trimmed).
 */
class ShouldFallbackTest {

    @Test
    fun `empty and whitespace-only text falls back`() {
        assertTrue(InternetPluginService.shouldFallback(""))
        assertTrue(InternetPluginService.shouldFallback("   \n\t "))
    }

    @Test
    fun `js shell page falls back`() {
        // What a SPA shell actually extracts to via jsoup.
        assertTrue(InternetPluginService.shouldFallback("Enable JavaScript to run this app."))
        assertTrue(InternetPluginService.shouldFallback("Loading…"))
    }

    @Test
    fun `just below the boundary falls back`() {
        val text = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS - 1)
        assertTrue(InternetPluginService.shouldFallback(text))
    }

    @Test
    fun `exactly at the boundary does not fall back`() {
        // length == MIN_USEFUL_CHARS → NOT thin (strictly-less comparison).
        val text = "x".repeat(InternetPluginService.MIN_USEFUL_CHARS)
        assertFalse(InternetPluginService.shouldFallback(text))
    }

    @Test
    fun `real prose does not fall back`() {
        val paragraph = ("Kenosis AI runs entirely offline on your device. " +
            "Models, transcription, and text-to-speech all ship as on-demand " +
            "asset packs, and the host app itself never holds the INTERNET ")
            .repeat(3)
        assertTrue(paragraph.length > InternetPluginService.MIN_USEFUL_CHARS)
        assertFalse(InternetPluginService.shouldFallback(paragraph))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before measuring`() {
        // 10 chars of content + 250 spaces → trimmed length 10 → falls back.
        val padded = "short text".padEnd(260)
        assertTrue(padded.length > InternetPluginService.MIN_USEFUL_CHARS)
        assertTrue(InternetPluginService.shouldFallback(padded))
    }
}