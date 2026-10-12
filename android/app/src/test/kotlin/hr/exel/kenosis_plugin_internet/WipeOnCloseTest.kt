package hr.exel.kenosis_plugin_internet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BrowserSettings] — the privacy knobs behind Settings:
 * Load images / Enable JavaScript / Wipe on close.
 *
 * Defaults are the ToS/privacy cut: images ON (a real browser loads them),
 * JavaScript ON (captchas and modern SERPs need it — the human-in-the-loop
 * solve must be possible), Wipe-on-close ON (cookies + site storage + cache
 * die with the activity).
 *
 * Pure JVM — [BrowserSettings.apply] / [BrowserSettings.toJson] never touch
 * SharedPreferences; only init/persist do.
 */
class WipeOnCloseTest {

    // ---------------- defaults ----------------

    @Test
    fun `defaults are load-images on, javascript on, wipe-on-close on`() {
        assertTrue(BrowserSettings.DEFAULT_LOAD_IMAGES)
        assertTrue(BrowserSettings.DEFAULT_JAVA_SCRIPT)
        assertTrue(BrowserSettings.DEFAULT_WIPE_ON_CLOSE)
    }

    @Test
    fun `wipe-on-close defaults to on`() {
        // The privacy default must be ON out of the box — a user who never
        // opens Settings still gets a wiped tab strip + cookie jar on exit.
        assertTrue(BrowserSettings.DEFAULT_WIPE_ON_CLOSE)
    }

    // ---------------- apply ----------------

    @Test
    fun `apply nulls leave the current value alone`() {
        BrowserSettings.apply(
            loadImages = BrowserSettings.DEFAULT_LOAD_IMAGES,
            javaScript = BrowserSettings.DEFAULT_JAVA_SCRIPT,
            wipeOnClose = BrowserSettings.DEFAULT_WIPE_ON_CLOSE,
        )
        BrowserSettings.apply(loadImages = null, javaScript = null, wipeOnClose = null)
        assertEquals(BrowserSettings.DEFAULT_LOAD_IMAGES, BrowserSettings.loadImages)
        assertEquals(BrowserSettings.DEFAULT_JAVA_SCRIPT, BrowserSettings.javaScript)
        assertEquals(BrowserSettings.DEFAULT_WIPE_ON_CLOSE, BrowserSettings.wipeOnClose)
    }

    @Test
    fun `apply writes each knob independently`() {
        BrowserSettings.apply(loadImages = true, javaScript = false, wipeOnClose = false)
        assertTrue(BrowserSettings.loadImages)
        assertFalse(BrowserSettings.javaScript)
        assertFalse(BrowserSettings.wipeOnClose)

        BrowserSettings.apply(loadImages = null, javaScript = true, wipeOnClose = true)
        assertTrue(BrowserSettings.loadImages) // untouched
        assertTrue(BrowserSettings.javaScript)
        assertTrue(BrowserSettings.wipeOnClose)

        // Reset to the privacy defaults for any test that runs after.
        BrowserSettings.apply(
            loadImages = BrowserSettings.DEFAULT_LOAD_IMAGES,
            javaScript = BrowserSettings.DEFAULT_JAVA_SCRIPT,
            wipeOnClose = BrowserSettings.DEFAULT_WIPE_ON_CLOSE,
        )
    }

    // ---------------- toJson (the settings channel payload) ----------------

    @Test
    fun `toJson emits the three settings keys`() {
        BrowserSettings.apply(loadImages = true, javaScript = false, wipeOnClose = true)
        val o = JSONObject(BrowserSettings.toJson())
        assertTrue(o.getBoolean("loadImages"))
        assertFalse(o.getBoolean("javaScript"))
        assertTrue(o.getBoolean("wipeOnClose"))

        BrowserSettings.apply(
            loadImages = BrowserSettings.DEFAULT_LOAD_IMAGES,
            javaScript = BrowserSettings.DEFAULT_JAVA_SCRIPT,
            wipeOnClose = BrowserSettings.DEFAULT_WIPE_ON_CLOSE,
        )
    }

    // ---------------- shouldWipeOnDestroy (the exit gate) ----------------

    @Test
    fun `wipe fires on a real user exit with no tool in flight`() {
        assertTrue(
            BrowserSettings.shouldWipeOnDestroy(
                isFinishing = true,
                isChangingConfigurations = false,
                invokeInFlight = false,
                wipeOnClose = true,
            ),
        )
    }

    @Test
    fun `presentUi recreate never wipes — not finishing`() {
        // presentUi (REORDER_TO_FRONT) and system teardown destroy/recreate
        // the activity while the service still owns the live tab WebViews.
        // Wiping there emptied the strip mid-web_search (2026-10-08).
        assertFalse(
            BrowserSettings.shouldWipeOnDestroy(
                isFinishing = false,
                isChangingConfigurations = false,
                invokeInFlight = false,
                wipeOnClose = true,
            ),
        )
    }

    @Test
    fun `config recreate never wipes`() {
        assertFalse(
            BrowserSettings.shouldWipeOnDestroy(
                isFinishing = true,
                isChangingConfigurations = true,
                invokeInFlight = false,
                wipeOnClose = true,
            ),
        )
    }

    @Test
    fun `in-flight invoke never wipes`() {
        // web_search / browser_fetch still reading those WebViews — a wipe
        // here destroys them mid-fetch and logs a bogus tool error.
        assertFalse(
            BrowserSettings.shouldWipeOnDestroy(
                isFinishing = true,
                isChangingConfigurations = false,
                invokeInFlight = true,
                wipeOnClose = true,
            ),
        )
    }

    @Test
    fun `wipe-on-close off never wipes`() {
        assertFalse(
            BrowserSettings.shouldWipeOnDestroy(
                isFinishing = true,
                isChangingConfigurations = false,
                invokeInFlight = false,
                wipeOnClose = false,
            ),
        )
    }

    // ---------------- TabManager wipe-on-close path ----------------

    @Test
    fun `closeAll drops every tab and reports them for WebView destroy`() {
        // The wipe-on-close handler calls TabManager.closeAll() then tears
        // down cookies / site storage / cache. closeAll must return every
        // dropped tab so the caller can destroy each WebView exactly once.
        val droppedIds = mutableListOf<Int>()
        val m = TabManager<Any>(
            maxTabs = 10,
            onTabDropped = { droppedIds += it.id },
        )
        m.openUrlTab("https://example.com/a", "a")
        m.openUrlTab("https://example.com/b", "b")
        m.openUrlTab("https://example.com/c", "c")

        val dropped = m.closeAll()
        assertEquals(3, dropped.size)
        assertEquals(dropped.map { it.id }, droppedIds)
        assertTrue(m.tabs.isEmpty())
    }

    @Test
    fun `close destroys just that tab and keeps the rest`() {
        val droppedIds = mutableListOf<Int>()
        val m = TabManager<Any>(
            maxTabs = 10,
            onTabDropped = { droppedIds += it.id },
        )
        val a = m.openUrlTab("https://example.com/a", "a")
        m.openUrlTab("https://example.com/b", "b")

        m.close(a.id)
        assertEquals(listOf(a.id), droppedIds)
        assertEquals(1, m.tabs.size)
    }
}
