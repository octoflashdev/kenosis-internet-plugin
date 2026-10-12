package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SERP URL-list resolvers the visible-tab search path
 * uses: [InternetPluginService.resolveDdgResultUrls] (now a URL-list filter
 * over the SERP tab's LINK_WALK_JS hrefs — there is no HTML scrape client)
 * and [InternetPluginService.parseEngineName].
 *
 * Pure JVM.
 */
class EngineResolverTest {

    // ---------------- resolveDdgResultUrls (URL list, not HTML) ----------------

    @Test
    fun `ddg l hrefs unwrap to their result targets in order`() {
        val raw = listOf(
            "https://html.duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Falpha&rut=x",
            "https://html.duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fbeta&rut=y",
        )
        assertEquals(
            listOf("https://example.com/alpha", "https://example.com/beta"),
            InternetPluginService.resolveDdgResultUrls(raw),
        )
    }

    @Test
    fun `plain https hrefs pass through when not ddg chrome`() {
        val raw = listOf("https://example.com/page")
        assertEquals(
            listOf("https://example.com/page"),
            InternetPluginService.resolveDdgResultUrls(raw),
        )
    }

    @Test
    fun `ddg chrome and app-gated hosts are dropped`() {
        val raw = listOf(
            "https://duckduckgo.com/y.js?ad=1",
            "https://html.duckduckgo.com/html/?q=more",
            "https://www.facebook.com/story.php",
            "https://example.com/page",
        )
        assertEquals(
            listOf("https://example.com/page"),
            InternetPluginService.resolveDdgResultUrls(raw),
        )
    }

    @Test
    fun `http-only and garbage hrefs are dropped`() {
        val raw = listOf(
            "http://example.com/insecure",
            "not-a-url",
            "javascript:void(0)",
            "https://example.com/secure",
        )
        assertEquals(
            listOf("https://example.com/secure"),
            InternetPluginService.resolveDdgResultUrls(raw),
        )
    }

    @Test
    fun `empty input resolves to empty`() {
        assertTrue(InternetPluginService.resolveDdgResultUrls(emptyList()).isEmpty())
    }

    @Test
    fun `unwrappable redirect hrefs are filtered when the host is the engine`() {
        // A leftover /l/ href whose target is not an http(s) URL stays the
        // redirector URL — which is ddg's own host — so it is dropped.
        val raw = listOf("https://html.duckduckgo.com/l/?uddg=not-a-url")
        assertTrue(InternetPluginService.resolveDdgResultUrls(raw).isEmpty())
    }

    // ---------------- parseEngineName ----------------

    @Test
    fun `duckduckgo normalizes to duckduckgo`() {
        assertEquals("duckduckgo", InternetPluginService.parseEngineName("duckduckgo"))
    }

    @Test
    fun `duckduckgo normalization is case-insensitive`() {
        assertEquals("duckduckgo", InternetPluginService.parseEngineName("DuckDuckGo"))
    }

    @Test
    fun `qwant normalizes to qwant`() {
        assertEquals("qwant", InternetPluginService.parseEngineName("qwant"))
    }

    @Test
    fun `google normalizes to google`() {
        assertEquals("google", InternetPluginService.parseEngineName("google"))
    }

    @Test
    fun `blank engine falls back to google`() {
        assertEquals("google", InternetPluginService.parseEngineName(""))
    }

    @Test
    fun `null engine falls back to google`() {
        assertEquals("google", InternetPluginService.parseEngineName(null))
    }

    @Test
    fun `unknown engine falls back to google`() {
        assertEquals("google", InternetPluginService.parseEngineName("bogus"))
    }

    @Test
    fun `whitespace engine falls back to google`() {
        assertEquals("google", InternetPluginService.parseEngineName("   "))
    }

    @Test
    fun `engine name is trimmed before matching`() {
        assertEquals("duckduckgo", InternetPluginService.parseEngineName("  duckduckgo  "))
    }
}
