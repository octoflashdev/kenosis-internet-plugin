package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException

/**
 * Tests for [describeFetchFailure] / [isTransportDeadError] — the 2026-09-26
 * on-device failure: the assistant model invented URLs (bbmedia.com /
 * batacel.com / bbcdn.com for a "current temperature in zagreb" ask); each
 * fetch burned 6–25 s across both paths and the error envelope was a raw
 * engine string that gave the model nothing to act on. The envelope must now
 * name the failure class and coach "run a web search instead", and a
 * transport-dead fast path must skip the guaranteed-nothing WebView retry.
 */
class FetchFailureTest {

    // ---------------- describeFetchFailure: the device failure classes ----------------

    @Test
    fun `webview unreachable message names the host and coaches a search`() {
        // The EXACT on-device string (ExecutionException.message carries the
        // cause's toString).
        val text = describeFetchFailure(
            "java.lang.IllegalStateException: the page could not be loaded " +
                "(net::ERR_ADDRESS_UNREACHABLE)",
            "https://www.bbmedia.com/weather/current/",
        )
        assertTrue(text, text.contains("'www.bbmedia.com'"))
        assertTrue(text, text.contains("could not be reached"))
        assertTrue(text, text.contains("Do not retry this URL"))
        assertTrue(text, text.contains("run a web search instead"))
    }

    @Test
    fun `dns failure says the site does not exist`() {
        val text = describeFetchFailure(
            "Unable to resolve host \"www.nosuchsite.example\": No address " +
                "associated with hostname",
            "https://www.nosuchsite.example/x",
        )
        assertTrue(text, text.contains("'www.nosuchsite.example'"))
        assertTrue(text, text.contains("does not exist"))
        assertTrue(text, text.contains("DNS"))
    }

    @Test
    fun `timeout message says the site did not respond`() {
        // bbcdn.com on-device: the thrown cause had no message — the class
        // simpleName "TimeoutException" was all the envelope carried.
        val text = describeFetchFailure(
            "TimeoutException",
            "https://www.bbcdn.com/weather/temp/zagreb",
        )
        assertTrue(text, text.contains("did not respond in time"))
    }

    @Test
    fun `unknown failure passes through verbatim`() {
        val raw = "the page rendered no readable content"
        assertEquals(raw, describeFetchFailure(raw, "https://example.com/"))
    }

    @Test
    fun `unparseable url degrades to the generic target`() {
        val text = describeFetchFailure(
            "the page could not be loaded (net::ERR_ADDRESS_UNREACHABLE)",
            "not a url",
        )
        // No quoted host — the URL never parsed into one.
        assertTrue(text, text.contains("The site exists but"))
    }

    // ---------------- isTransportDeadError: the WebView-skip predicate ----------------

    @Test
    fun `execution-wrapped unknown host is transport dead`() {
        // fetchAndExtract's fast error is the future's ExecutionException.
        assertTrue(
            isTransportDeadError(
                ExecutionException(
                    UnknownHostException(
                        "Unable to resolve host \"www.bbmedia.com\": No " +
                            "address associated with hostname",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `connect exception is transport dead`() {
        assertTrue(
            isTransportDeadError(
                ExecutionException(ConnectException("Failed to connect to 'www.bbcdn.com'/13.223.25.84:443")),
            ),
        )
    }

    @Test
    fun `webview-reported unreachable is transport dead`() {
        assertTrue(
            isTransportDeadError(
                IllegalStateException(
                    "java.lang.IllegalStateException: the page could not be " +
                        "loaded (net::ERR_ADDRESS_UNREACHABLE)",
                ),
            ),
        )
    }

    @Test
    fun `bot-wall http 403 is NOT transport dead — the webview retry is the recovery path`() {
        assertFalse(
            isTransportDeadError(
                ExecutionException(IllegalStateException("HTTP 403")),
            ),
        )
    }

    @Test
    fun `http 500 is not transport dead`() {
        assertFalse(
            isTransportDeadError(
                ExecutionException(IllegalStateException("HTTP 500")),
            ),
        )
    }

    @Test
    fun `null is not transport dead`() {
        assertFalse(isTransportDeadError(null))
    }

    @Test
    fun `our own future timeout is not transport dead (unproven, let the webview try)`() {
        // A java.util.concurrent.TimeoutException from the .get() wrapper
        // says nothing about the transport — the WebView gets its chance.
        assertFalse(
            isTransportDeadError(java.util.concurrent.TimeoutException()),
        )
    }
}
