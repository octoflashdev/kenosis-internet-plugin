package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fetch-log disk codec (the "Requested URLs (0)" fix): the log is
 * persisted to filesDir/fetch_log.json because Android kills the plugin
 * process between host rounds, so the UI's process must rehydrate the log
 * from disk. These tests pin the round-trip the service relies on —
 * encodeFetchLogOldestFirst → decodeFetchLog — plus the cap and the
 * malformed-row resilience (one bad row must not wipe the log).
 *
 * Runs on the dev machine only:
 *   cd plugins/internet/android && ./gradlew :app:testDebugUnitTest
 */
class FetchLogJsonTest {

    private fun record(
        timestamp: Long = 1_700_000_000_000L,
        tool: String = "browser_fetch",
        query: String? = "temperature in zagreb",
        requestedUrl: String = "https://www.accuweather.com/zagreb",
        finalUrl: String? = "https://www.accuweather.com/en/hr/zagreb",
        title: String = "Zagreb Weather",
        chars: Int = 4213,
        path: String = "http",
        status: String = "ok",
    ) = InternetPluginService.Companion.FetchRecord(
        timestamp = timestamp, tool = tool, query = query,
        requestedUrl = requestedUrl, finalUrl = finalUrl, title = title,
        chars = chars, path = path, status = status,
    )

    @Test
    fun `round trip preserves every field`() {
        val original = listOf(
            record(),
            record(timestamp = 2L, query = null, finalUrl = null, status = "search",
                   tool = "web_search", requestedUrl = "https://google.com/search?q=x",
                   title = "search request", chars = 0, path = ""),
            record(timestamp = 3L, status = "error:HTTP 403", finalUrl = null),
        )
        val decoded = InternetPluginService.decodeFetchLog(
            InternetPluginService.encodeFetchLogOldestFirst(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `null query and finalUrl survive as NULL json values not strings`() {
        val json = InternetPluginService.recordToJson(record(query = null, finalUrl = null))
        // Stored as JSON null — NOT the string "null" (which would render as
        // a literal 'null' query row in the UI after hydration).
        assertTrue(json.isNull("query"))
        assertTrue(json.isNull("finalUrl"))
        val back = InternetPluginService.recordFromJson(json)!!
        assertNull(back.query)
        assertNull(back.finalUrl)
    }

    @Test
    fun `decode caps at the limit keeping the newest entries`() {
        val records = (1..250).map { record(timestamp = it.toLong()) }
        val encoded = InternetPluginService.encodeFetchLogOldestFirst(records)
        val decoded = InternetPluginService.decodeFetchLog(encoded, cap = 200)
        assertEquals(200, decoded.size)
        // Oldest-first encode → the survivors must be timestamps 51..250.
        assertEquals(51L, decoded.first().timestamp)
        assertEquals(250L, decoded.last().timestamp)
    }

    @Test
    fun `a malformed row is skipped not the whole log`() {
        val good1 = record(timestamp = 10L)
        val good2 = record(timestamp = 20L)
        val json1 = InternetPluginService.encodeFetchLogOldestFirst(listOf(good1))
        val json2 = InternetPluginService.encodeFetchLogOldestFirst(listOf(good2))
        // Splice a row without required fields between the two good rows:
        // [ {good1}, {"tool":"no required fields"}, {good2} ]
        val text = json1.dropLast(1) + "," + """{"tool":"no required fields"},""" +
            json2.drop(1)
        val decoded = InternetPluginService.decodeFetchLog(text)
        assertEquals(listOf(good1, good2), decoded)
    }

    @Test
    fun `a non-JSON file decodes to an empty log not a crash`() {
        assertTrue(InternetPluginService.decodeFetchLog("<html>not json").isEmpty())
        assertTrue(InternetPluginService.decodeFetchLog("").isEmpty())
    }
}