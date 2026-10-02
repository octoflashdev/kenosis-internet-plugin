package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for the per-engine `web_search` result resolvers (+73):
 * [InternetPluginService.resolveDdgResultUrls] (html.duckduckgo.com HTML
 * SERP) and [InternetPluginService.resolveQwantResultUrls] (api.qwant.com
 * v3 JSON), plus [InternetPluginService.parseEngineName]. Pure parsers —
 * fixtures are trimmed shapes of real SERP/API bodies.
 *
 * The resolvers return the CANDIDATE LIST (ordered, deduped, capped at 10),
 * not a single URL: the caller needs the full list to skip an app-gated top
 * pick that can never render (see AppGateFilterTest).
 */
class EngineResolverTest {

    // ---------------- DuckDuckGo (resolveDdgResultUrls) ----------------

    @Test
    fun `ddg redirect-style result unwraps the uddg param`() {
        // Typical html.duckduckgo.com result: a /l/?uddg=…&rut=… redirect.
        val html = """
            <html><body>
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.windfinder.com%2Fforecast%2Fsplit&amp;rut=abc123">Windfinder Split</a>
            </div>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://www.windfinder.com/forecast/split"),
            InternetPluginService.resolveDdgResultUrls(html),
        )
    }

    @Test
    fun `ddg direct absolute result is returned as-is`() {
        val html = """
            <html><body>
            <a class="result__a" href="https://example.com/page">Example</a>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/page"),
            InternetPluginService.resolveDdgResultUrls(html),
        )
    }

    @Test
    fun `ddg protocol-relative href is upgraded to https`() {
        val html = """
            <html><body>
            <a class="result__a" href="//example.com/page">Example</a>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/page"),
            InternetPluginService.resolveDdgResultUrls(html),
        )
    }

    @Test
    fun `ddg empty serp returns an empty list`() {
        assertTrue(
            InternetPluginService.resolveDdgResultUrls("<html><body></body></html>").isEmpty(),
        )
    }

    @Test
    fun `ddg rate-limit page (no result anchor) returns an empty list`() {
        // What an anomaly-detected block / rate-limit page extracts to: no
        // a.result__a → the caller surfaces a clear "no results" envelope.
        assertTrue(
            InternetPluginService.resolveDdgResultUrls(
                "<html><body><p>If this error persists</p></body></html>",
            ).isEmpty(),
        )
    }

    @Test
    fun `ddg relative non-redirect href is rejected`() {
        val html = """
            <html><body>
            <a class="result__a" href="/result/xyz">Local link</a>
            </body></html>
        """.trimIndent()
        assertTrue(InternetPluginService.resolveDdgResultUrls(html).isEmpty())
    }

    @Test
    fun `ddg multiple results keep order and dedupe`() {
        val html = """
            <html><body>
            <a class="result__a" href="https://a.example.com/1">A</a>
            <a class="result__a" href="https://b.example.com/2">B</a>
            <a class="result__a" href="https://a.example.com/1">A again</a>
            <a class="result__a" href="https://c.example.com/3">C</a>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://a.example.com/1",
                "https://b.example.com/2",
                "https://c.example.com/3",
            ),
            InternetPluginService.resolveDdgResultUrls(html),
        )
    }

    @Test
    fun `ddg result list caps at ten`() {
        val anchors = (1..12).joinToString("\n") {
            """<a class="result__a" href="https://example.com/$it">R$it</a>"""
        }
        val urls = InternetPluginService.resolveDdgResultUrls("<html><body>$anchors</body></html>")
        assertEquals(10, urls.size)
        assertEquals("https://example.com/10", urls.last())
    }

    // ---------------- Qwant (resolveQwantResultUrls) ----------------

    @Test
    fun `qwant web result url is returned`() {
        val json = """
            {"status":"success","data":{"result":{
              "items":{"mainline":[
                {"type":"web","items":[{"type":"web","url":"https://example.com/a","title":"A"}]},
                {"type":"images","items":[]}
              ]}}}}
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/a"),
            InternetPluginService.resolveQwantResultUrls(json),
        )
    }

    @Test
    fun `qwant skips an empty first mainline group`() {
        val json = """
            {"status":"success","data":{"result":{
              "items":{"mainline":[
                {"type":"news","items":[]},
                {"type":"web","items":[{"type":"web","url":"https://example.com/b","title":"B"}]}
              ]}}}}
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/b"),
            InternetPluginService.resolveQwantResultUrls(json),
        )
    }

    @Test
    fun `qwant malformed json returns an empty list`() {
        assertTrue(InternetPluginService.resolveQwantResultUrls("not json at all").isEmpty())
    }

    @Test
    fun `qwant rate-limit body (no data) returns an empty list`() {
        // Qwant 403/429 bodies carry a "limit exceeded"-style message and no
        // data.result path → empty → the caller surfaces a clear error envelope.
        assertTrue(
            InternetPluginService.resolveQwantResultUrls(
                """{"status":"error","data":{"error":"too many requests"}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `qwant empty result set returns an empty list`() {
        val json = """
            {"status":"success","data":{"result":{"items":{"mainline":[]}}}}
        """.trimIndent()
        assertTrue(InternetPluginService.resolveQwantResultUrls(json).isEmpty())
    }

    @Test
    fun `qwant non-http url is skipped`() {
        val json = """
            {"data":{"result":{"items":{"mainline":[
              {"type":"web","items":[{"type":"web","url":"ftp://example.com/x"}]},
              {"type":"web","items":[{"type":"web","url":"https://example.com/ok"}]}
            ]}}}}
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/ok"),
            InternetPluginService.resolveQwantResultUrls(json),
        )
    }

    @Test
    fun `qwant multiple results keep order and dedupe`() {
        val json = """
            {"data":{"result":{"items":{"mainline":[
              {"type":"web","items":[
                {"type":"web","url":"https://a.example.com/1"},
                {"type":"web","url":"https://b.example.com/2"},
                {"type":"web","url":"https://a.example.com/1"}
              ]},
              {"type":"news","items":[
                {"type":"news","url":"https://c.example.com/3"}
              ]}
            ]}}}}
        """.trimIndent()
        assertEquals(
            listOf("https://a.example.com/1", "https://b.example.com/2", "https://c.example.com/3"),
            InternetPluginService.resolveQwantResultUrls(json),
        )
    }

    @Test
    fun `qwant viewer-decorated body (webview bot-block fallback) still resolves`() {
        // When the SERP was served through the hidden WebView (DataDome 403s
        // plain OkHttp from the device), Chrome's JSON viewer can put its
        // toolbar text around the JSON in innerText. The resolver carves the
        // first '{' … last '}' region before parsing.
        val rendered = """
            Copy Pretty print Raw data {"status":"success","data":{"result":{
              "items":{"mainline":[
                {"type":"web","items":[{"type":"web","url":"https://example.com/a","title":"A"}]}
              ]}}}} Download
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/a"),
            InternetPluginService.resolveQwantResultUrls(rendered),
        )
    }

    @Test
    fun `qwant body with no json region returns an empty list`() {
        assertTrue(
            InternetPluginService.resolveQwantResultUrls("Copy Pretty print Raw data").isEmpty(),
        )
        assertTrue(
            InternetPluginService.resolveQwantResultUrls("} leading brace only {").isEmpty(),
        )
    }

    // ---------------- Qwant API contract (QWANT_API_URL) ----------------

    @Test
    fun `qwant api url requests exactly ten results`() {
        // The v3 API hard-rejects any other count with HTTP 400 —
        // "count must be equal to 10" (verified 2026-09-15). The plugin ran
        // with count=5 since +73 and every qwant web_search failed 400.
        assertTrue(
            "QWANT_API_URL must ask for count=10: ${InternetPluginService.QWANT_API_URL}",
            InternetPluginService.QWANT_API_URL.contains("count=10")
        )
    }

    // ---------------- engine name normalization (parseEngineName) ----------------

    @Test
    fun `engine names normalize case and whitespace`() {
        assertEquals(
            "duckduckgo",
            InternetPluginService.parseEngineName("  DuckDuckGo "),
        )
        assertEquals("qwant", InternetPluginService.parseEngineName("QWANT"))
    }

    @Test
    fun `unknown or missing engine falls back to google`() {
        // Today's behavior for anything the host didn't set: google.
        assertEquals("google", InternetPluginService.parseEngineName("bing"))
        assertEquals("google", InternetPluginService.parseEngineName(""))
        assertEquals("google", InternetPluginService.parseEngineName(null))
    }
}