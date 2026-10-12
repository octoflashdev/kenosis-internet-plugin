package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InternetPluginService.isAppGatedUrl] — the pure host
 * filter that keeps app-deep-link hosts out of the "I'm Lucky" pick (those
 * pages can never render in a tab; they bounce to `fb://` / `intent://`).
 *
 * The candidate-list pick itself lives in [FirstLinkPickTest].
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
}
