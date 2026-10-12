package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for [TabManager] — the visible tab-strip bookkeeping of the plugin
 * mini-browser. Pure JVM: [TabManager] is generic over the WebView type, so
 * these tests use `TabManager<String>`.
 *
 * What this guards (the ToS compliance cut): web_search opens a VISIBLE
 * SERP + Lucky pair, browser_fetch opens a VISIBLE page tab, the strip is
 * capped with FIFO rotation, and wipe-on-close can clear every tab.
 */
class TabManagerTest {

    private fun manager(max: Int = TabManager.MAX_TABS): TabManager<String> =
        TabManager(maxTabs = max)

    // ---------------- cap + FIFO ----------------

    @Test
    fun `cap is ten by default`() {
        assertEquals(10, TabManager.MAX_TABS)
    }

    @Test
    fun `opening past the cap drops the oldest tab`() {
        val m = manager()
        repeat(11) { i -> m.openUrlTab("https://example.com/$i") }
        val tabs = m.tabs
        assertEquals(TabManager.MAX_TABS, tabs.size)
        // The oldest (…/0) is gone; the newest (…/10) is present.
        assertNull(tabs.firstOrNull { it.url.endsWith("/0") })
        assertNotNull(tabs.firstOrNull { it.url.endsWith("/10") })
        assertEquals("https://example.com/1", tabs.first().url)
        assertEquals("https://example.com/10", tabs.last().url)
    }

    @Test
    fun `custom max is honored`() {
        val m = manager(max = 2)
        m.openUrlTab("https://a.example/")
        m.openUrlTab("https://b.example/")
        m.openUrlTab("https://c.example/")
        assertEquals(listOf("https://b.example/", "https://c.example/"), m.tabs.map { it.url })
    }

    // ---------------- active / foreground ----------------

    @Test
    fun `openUrlTab is the active tab`() {
        val m = manager()
        val t = m.openUrlTab("https://example.com/page")
        assertTrue(t.isForeground)
        assertEquals(t.id, m.activeTab?.id)
    }

    @Test
    fun `a new tab takes foreground from the previous one`() {
        val m = manager()
        val a = m.openUrlTab("https://a.example/")
        val b = m.openUrlTab("https://b.example/")
        assertFalse(m.tabs.first { it.id == a.id }.isForeground)
        assertTrue(m.tabs.first { it.id == b.id }.isForeground)
        assertEquals(b.id, m.activeTab?.id)
    }

    @Test
    fun `setActive moves the foreground flag`() {
        val m = manager()
        val a = m.openUrlTab("https://a.example/")
        val b = m.openUrlTab("https://b.example/")
        m.setActive(a.id)
        assertTrue(m.tabs.first { it.id == a.id }.isForeground)
        assertFalse(m.tabs.first { it.id == b.id }.isForeground)
        assertEquals(a.id, m.activeTab?.id)
    }

    // ---------------- search pair (SERP + Lucky) ----------------

    @Test
    fun `openSearchTabs keeps the SERP in front until a result opens`() {
        // 2026-10-07: Lucky used to be foregrounded on an UNLOADED WebView
        // while the real Google page sat in the background tab — "opened tab
        // was empty, hidden tab was the search page". Focus follows content.
        val m = manager()
        val (serp, lucky) = m.openSearchTabs("weather zagreb", "google")
        assertTrue(serp.isForeground)
        assertFalse(lucky.isForeground)
        assertEquals(2, m.tabs.size)
        assertEquals(serp.id, m.activeTab?.id)
    }

    @Test
    fun `search pair carries the engine SERP url on the proof tab only`() {
        val m = manager()
        val (serp, lucky) = m.openSearchTabs("weather zagreb", "google")
        // Exact form — /search with the Web-tab flag, nothing else riding it.
        assertEquals("https://www.google.com/search?q=weather+zagreb&udm=14", serp.url)
        // Lucky is the result slot: no URL until the 1st openable link is
        // known (it must not claim the SERP URL its WebView never loaded).
        assertEquals("", lucky.url)
    }

    @Test
    fun `navigate moves the lucky tab to the picked link`() {
        val m = manager()
        val (serp, lucky) = m.openSearchTabs("weather zagreb", "duckduckgo")
        val moved = m.navigate(lucky.id, "https://weather.example/zagreb", "Zagreb weather")
        assertNotNull(moved)
        assertEquals("https://weather.example/zagreb", m.tabs.first { it.id == lucky.id }.url)
        assertEquals("Zagreb weather", m.tabs.first { it.id == lucky.id }.title)
        // SERP stays put — the proof of what was asked.
        assertEquals(serp.url, m.tabs.first { it.id == serp.id }.url)
    }

    @Test
    fun `activating the lucky tab after the pick puts the result in front`() {
        val m = manager()
        val (serp, lucky) = m.openSearchTabs("weather zagreb", "google")
        m.navigate(lucky.id, "https://weather.example/zagreb", "Zagreb weather")
        m.setActive(lucky.id)
        assertTrue(m.tabs.first { it.id == lucky.id }.isForeground)
        assertFalse(m.tabs.first { it.id == serp.id }.isForeground)
        assertEquals(lucky.id, m.activeTab?.id)
    }

    // ---------------- serpUrl (no IFL backdoor) ----------------

    @Test
    fun `serpUrl is a plain google web-tab search — no IFL auto-navigate`() {
        val url = TabManager.serpUrl("hello world", "google")
        // udm=14 = the "Web" tab (classic result anchors, less AI-Mode chrome).
        // Still a plain /search — never an IFL auto-navigate.
        assertEquals("https://www.google.com/search?q=hello+world&udm=14", url)
    }

    @Test
    fun `serpUrl covers ddg and qwant and unknown falls back to google`() {
        assertTrue(TabManager.serpUrl("x", "duckduckgo").startsWith("https://html.duckduckgo.com/"))
        assertTrue(TabManager.serpUrl("x", "qwant").startsWith("https://www.qwant.com/"))
        assertTrue(TabManager.serpUrl("x", "unknown").startsWith("https://www.google.com/"))
    }

    // ---------------- chrome updates + close ----------------

    @Test
    fun `updateChrome follows redirects and titles`() {
        val m = manager()
        val t = m.openUrlTab("https://example.com/start", "Start")
        m.updateChrome(t.id, title = "Final", url = "https://example.com/final")
        val now = m.tabs.single()
        assertEquals("Final", now.title)
        assertEquals("https://example.com/final", now.url)
    }

    @Test
    fun `close keeps a foreground tab among the rest`() {
        val m = manager()
        val a = m.openUrlTab("https://a.example/")
        val b = m.openUrlTab("https://b.example/")
        m.close(b.id)
        assertEquals(1, m.tabs.size)
        assertTrue(m.tabs.single().isForeground)
        assertEquals(a.id, m.activeTab?.id)
    }

    @Test
    fun `closeAll drops every tab and returns them`() {
        val m = manager()
        m.openUrlTab("https://a.example/")
        val (serp, lucky) = m.openSearchTabs("q", "google")
        val dropped = m.closeAll()
        assertEquals(3, dropped.size)
        assertTrue(m.tabs.isEmpty())
        assertNull(m.activeTab)
        assertTrue(dropped.any { it.id == serp.id })
        assertTrue(dropped.any { it.id == lucky.id })
    }

    @Test
    fun `explicit webView rides the tab — no factory fallback`() {
        val m = TabManager<String>(maxTabs = TabManager.MAX_TABS)
        val t = m.openUrlTab("https://example.com/", webView = "explicit")
        assertEquals("explicit", t.webView)
        val bare = m.openUrlTab("https://example.com/bare")
        assertNull(bare.webView)
    }

    // ---------------- isEngineRedirector (the T1 carve-out's single source) ----------------

    @Test
    fun `result redirector shapes recognized — the outbound pointer family`() {
        for (url in listOf(
            "https://www.google.com/goto?url=CAES-x", // encrypted goto token
            "https://www.google.com/url?q=https%3A%2F%2Fexample.com",
            "https://www2.duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com",
            "https://lite.qwant.com/redirect?q=abc",
        )) {
            assertTrue(url, TabManager.isEngineRedirector(url))
        }
    }

    @Test
    fun `non-redirector shapes are NOT redirectors`() {
        for (url in listOf(
            "https://www.google.com/search?q=x", // engine CONTENT page
            "https://example.com/goto?url=x", // path on a non-engine host
            "https://subdomain.example.org/goto-page", // a path is not a redirector
            "https://example.com/url?q=x",
            "not a url",
        )) {
            assertFalse(url, TabManager.isEngineRedirector(url))
        }
    }
}
