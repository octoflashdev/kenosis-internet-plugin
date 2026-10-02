package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CaptchaGate] — the process-shared "human check needed" state
 * between the service (writer) and MainActivity's UI/notification layers
 * (readers).
 *
 * The object is process-global, so every test resets it in setup. The
 * staleness boundary is the part that must NOT regress silently: a stale
 * gate keeps a banner/notification pointing at a challenge that (per the
 * engine's own cookie schedule) may no longer exist.
 */
class CaptchaGateTest {

    @Before
    fun reset() {
        CaptchaGate.clear("duckduckgo")
        CaptchaGate.clear("qwant")
        CaptchaGate.clear("anything-else")
    }

    // ---------------- set / current ----------------

    @Test
    fun `set arms the gate and current returns it`() {
        CaptchaGate.set("qwant", "https://api.qwant.com/v3/search/web?q=x", "HTTP 403", nowMs = 1000L)
        val p = CaptchaGate.current(nowMs = 2000L)
        assertEquals("qwant", p?.engine)
        assertEquals("https://api.qwant.com/v3/search/web?q=x", p?.url)
        assertEquals("HTTP 403", p?.reason)
        assertEquals(1000L, p?.sinceMs)
    }

    @Test
    fun `current with no gate is null`() {
        assertNull(CaptchaGate.current(nowMs = 1000L))
    }

    @Test
    fun `set refreshes the same engine in place`() {
        CaptchaGate.set("qwant", "https://api.qwant.com/v3/search/web?q=a", "HTTP 403", nowMs = 1000L)
        CaptchaGate.set("qwant", "https://api.qwant.com/v3/search/web?q=b", "HTTP 403", nowMs = 5000L)
        val p = CaptchaGate.current(nowMs = 6000L)
        assertEquals("b", p?.url?.takeLast(1))
        assertEquals(5000L, p?.sinceMs)
    }

    // ---------------- clear ----------------

    @Test
    fun `clear drops the matching engine`() {
        CaptchaGate.set("qwant", "https://api.qwant.com", "HTTP 403", nowMs = 1000L)
        CaptchaGate.clear("qwant")
        assertNull(CaptchaGate.current(nowMs = 2000L))
    }

    @Test
    fun `clear of a DIFFERENT engine leaves the gate armed`() {
        // A stale solve (old screen, old round) must not dismiss a newer
        // engine's check.
        CaptchaGate.set("duckduckgo", "https://html.duckduckgo.com/html/?q=x", "HTTP 403", nowMs = 1000L)
        CaptchaGate.clear("qwant")
        assertEquals("duckduckgo", CaptchaGate.current(nowMs = 2000L)?.engine)
    }

    @Test
    fun `a new engine replaces the pending one`() {
        // One check solved at a time; the next blocked search re-arms.
        CaptchaGate.set("qwant", "https://api.qwant.com", "HTTP 403", nowMs = 1000L)
        CaptchaGate.set("duckduckgo", "https://html.duckduckgo.com", "HTTP 429", nowMs = 2000L)
        val p = CaptchaGate.current(nowMs = 3000L)
        assertEquals("duckduckgo", p?.engine)
    }

    // ---------------- staleness ----------------

    @Test
    fun `gate is fresh just under the TTL and stale just past it`() {
        val since = 0L
        CaptchaGate.set("qwant", "https://api.qwant.com", "HTTP 403", nowMs = since)
        val ttl = CaptchaGate.STALE_TTL_MS
        assertFalse(CaptchaGate.isStale(CaptchaGate.pending, nowMs = ttl, ttlMs = ttl))
        assertTrue(CaptchaGate.isStale(CaptchaGate.pending, nowMs = ttl + 1, ttlMs = ttl))
    }

    @Test
    fun `current drops a stale gate (self-cleaning, no banner for a dead challenge)`() {
        CaptchaGate.set("qwant", "https://api.qwant.com", "HTTP 403", nowMs = 0L)
        assertNull(CaptchaGate.current(nowMs = CaptchaGate.STALE_TTL_MS + 1))
        // And the drop is persistent, not per-read.
        assertNull(CaptchaGate.current(nowMs = CaptchaGate.STALE_TTL_MS + 2))
    }

    @Test
    fun `isStale of null is never stale`() {
        assertFalse(CaptchaGate.isStale(null, nowMs = Long.MAX_VALUE))
    }

    // ---------------- solveUrlForHuman ----------------

    @Test
    fun `solve URL normalizes a blocked endpoint to its site origin`() {
        // The gate used to record the EXACT blocked URL — for Qwant that is
        // a JSON API endpoint, and the visible solve WebView rendered raw
        // JSON ("some code"). The human-facing origin earns the same
        // domain-scoped clearance cookies.
        assertEquals(
            "https://api.qwant.com/",
            solveUrlForHuman("https://api.qwant.com/v3/search/web?q=kenosis&t=web"),
        )
        assertEquals(
            "https://html.duckduckgo.com/",
            solveUrlForHuman("https://html.duckduckgo.com/html/?q=kenosis"),
        )
    }

    @Test
    fun `solve URL keeps non-http and unparseable URLs unchanged`() {
        // Never crash the arming path: the exact URL still renders, just
        // less friendly.
        assertEquals("ftp://example.com/x", solveUrlForHuman("ftp://example.com/x"))
        // A raw space makes URI parsing throw — degrade to the exact URL.
        assertEquals(
            "https://example.com/a b",
            solveUrlForHuman("https://example.com/a b"),
        )
    }
}
