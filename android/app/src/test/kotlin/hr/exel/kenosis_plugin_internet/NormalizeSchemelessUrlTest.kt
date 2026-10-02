package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary tests for InternetPluginService.normalizeSchemelessUrl — the +73 i
 * backstop that lets a bare domain (`example.com`) fetch directly instead of
 * erroring with "url must be an absolute http(s) URL". Only a WHOLE-string
 * domain gets `https://` prepended; prose, garbage, and already-schemed URLs
 * pass through unchanged (the downstream scheme check keeps today's error).
 */
class NormalizeSchemelessUrlTest {

    @Test
    fun `bare domains get the scheme prepended`() {
        assertEquals("https://example.com",
            InternetPluginService.normalizeSchemelessUrl("example.com"))
        assertEquals("https://www.site.org",
            InternetPluginService.normalizeSchemelessUrl("www.site.org"))
    }

    @Test
    fun `domain with path query or fragment gets the scheme prepended`() {
        assertEquals("https://www.site.org/path?q=1",
            InternetPluginService.normalizeSchemelessUrl("www.site.org/path?q=1"))
        assertEquals("https://site.com:8080/x",
            InternetPluginService.normalizeSchemelessUrl("site.com:8080/x"))
        assertEquals("https://a.b.example.co.uk#section",
            InternetPluginService.normalizeSchemelessUrl("a.b.example.co.uk#section"))
    }

    @Test
    fun `already-schemed urls pass through unchanged`() {
        assertEquals("https://example.com",
            InternetPluginService.normalizeSchemelessUrl("https://example.com"))
        assertEquals("http://example.com/path",
            InternetPluginService.normalizeSchemelessUrl("http://example.com/path"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://example.com",
            InternetPluginService.normalizeSchemelessUrl("  example.com  "))
    }

    @Test
    fun `prose and commands never match`() {
        // The literal bug that motivated +73 k — the model echoed the
        // utterance as the query; a sentence is never a domain.
        assertEquals("do search internet",
            InternetPluginService.normalizeSchemelessUrl("do search internet"))
        // A domain embedded mid-sentence is not a whole-string domain — the
        // detection prompt, not a regex, handles that case.
        assertEquals("visit example.com today",
            InternetPluginService.normalizeSchemelessUrl("visit example.com today"))
        assertEquals("weather in zagreb",
            InternetPluginService.normalizeSchemelessUrl("weather in zagreb"))
    }

    @Test
    fun `non-url single words and fragments never match`() {
        assertEquals("hello", InternetPluginService.normalizeSchemelessUrl("hello"))
        assertEquals("15 times 12", InternetPluginService.normalizeSchemelessUrl("15 times 12"))
        // No TLD letters after the final dot — a bare abbreviation, not a host.
        assertEquals("e.g.", InternetPluginService.normalizeSchemelessUrl("e.g."))
    }

    @Test
    fun `uppercase domains still match and keep the lowercase scheme`() {
        assertEquals("https://EXAMPLE.com",
            InternetPluginService.normalizeSchemelessUrl("EXAMPLE.com"))
    }
}