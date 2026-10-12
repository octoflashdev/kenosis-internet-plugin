package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for [HtmlTextExtractor] — the Tier-1 block-walk text extraction
 * (plan: anonymous-first-tiered-web-fetch). Pure JVM (Jsoup has no Android
 * types). The fixture round trip mirrors what the visible-tab extractor
 * serves: script/style stripped, ONE line per BLOCK element, whitespace
 * collapsed, nested block containers emitted once.
 */
class HtmlTextExtractorTest {

    // ---------------- basic round trip ----------------

    @Test
    fun `static article yields one line per block element`() {
        val html = """
            <html><head><title>Zagreb weather</title>
              <style>body { color: red; }</style>
              <script>alert("payload");</script>
            </head><body>
              <h1>Zagreb weather</h1>
              <p>Tomorrow: sunny, 21°C.</p>
              <ul>
                <li>Monday: cloudy</li>
                <li>Tuesday: rain</li>
              </ul>
            </body></html>
        """.trimIndent()
        val page = HtmlTextExtractor.extract(html)
        assertEquals("Zagreb weather", page.title)
        assertEquals(
            listOf(
                "Zagreb weather", // h1 — the <title> line is separate, text body unchanged
                "Tomorrow: sunny, 21°C.",
                "Monday: cloudy",
                "Tuesday: rain",
            ),
            page.text.split('\n'),
        )
    }

    @Test
    fun `script style noscript iframe template text never reaches the extract`() {
        val html = """
            <html><body>
              <p>Real content</p>
              <script>document.write("EVIL-PAYLOAD-TEXT");</script>
              <style>.x { content: "CSS-PAYLOAD-TEXT"; }</style>
              <noscript>NOSCRIPT-PAYLOAD</noscript>
              <iframe srcdoc="IFRAME-PAYLOAD"></iframe>
              <template>TEMPLATE-PAYLOAD</template>
            </body></html>
        """.trimIndent()
        val page = HtmlTextExtractor.extract(html)
        for (marker in listOf("EVIL-PAYLOAD-TEXT", "CSS-PAYLOAD-TEXT", "NOSCRIPT-PAYLOAD", "IFRAME-PAYLOAD", "TEMPLATE-PAYLOAD")) {
            assertTrue("marker '$marker' leaked", marker !in page.text)
        }
        assertEquals("Real content", page.text)
    }

    // ---------------- nested-block dedupe ----------------

    @Test
    fun `nested block containers emit the leafmost block only`() {
        val html = """
            <html><body>
              <blockquote><p>The quoted sentence.</p></blockquote>
              <table><tr><th>Day</th><td><p>21°C</p></td></tr></table>
              <ol><li>Outer <ul><li>Inner</li></ul></li></ol>
            </body></html>
        """.trimIndent()
        val lines = HtmlTextExtractor.extract(html).text.split('\n')
        // blockquote wraps a p → only the p; td wraps a p → only the p; the
        // outer li wraps a ul → the outer li's non-block text ("Outer") plus
        // the inner li's line.
        assertTrue(lines.contains("The quoted sentence."))
        assertTrue(lines.contains("Day"))
        assertTrue(lines.contains("21°C"))
        assertTrue(lines.contains("Inner"))
        // No duplicated full-container lines.
        assertTrue(lines.count { it == "The quoted sentence." } == 1)
        assertTrue(lines.none { it.contains("The quoted sentence.") && it.length > "The quoted sentence.".length })
    }

    // ---------------- whitespace + caps ----------------

    @Test
    fun `whitespace collapses inside a line`() {
        val html = "<html><body><p>Line\twith   lots  of\nspaces</p></body></html>"
        assertEquals(
            "Line with lots of spaces",
            HtmlTextExtractor.extract(html).text,
        )
    }

    @Test
    fun `per-line cap trims one runaway element without dropping the rest`() {
        val blob = "y".repeat(5000)
        val html = "<html><body><pre>$blob</pre><p>after the blob</p></body></html>"
        val lines = HtmlTextExtractor.extract(html).text.split('\n')
        assertEquals(2, lines.size)
        assertEquals(HtmlTextExtractor.MAX_LINE_CHARS, lines[0].length)
        assertEquals("after the blob", lines[1])
    }

    @Test
    fun `total cap truncates and skips the overrun lines`() {
        val html = StringBuilder("<html><body>")
        for (i in 0 until 30) {
            val filler = "z".repeat(200) + "-$i"
            html.append("<p>$filler</p>")
        }
        html.append("</body></html>")
        val text = HtmlTextExtractor.extract(html.toString()).text
        // 30 lines × 204 chars each = 6120 > 24576? no — 30 × 204 = 6120 < cap.
        // So force the cap instead: assert the full round trip holds here.
        assertTrue(text.startsWith("z"))
        val rich = StringBuilder("<html><body>")
        for (i in 0 until 200) {
            val filler = "z".repeat(180) + "-$i"
            rich.append("<p>$filler</p>")
        }
        rich.append("</body></html>")
        val capped = HtmlTextExtractor.extract(rich.toString()).text
        assertTrue(
            "total cap exceeded: ${capped.length}",
            capped.length <= HtmlTextExtractor.MAX_TOTAL_CHARS,
        )
    }

    @Test
    fun `blank and empty inputs yield empty text`() {
        assertEquals("", HtmlTextExtractor.extract("").text)
        assertEquals("", HtmlTextExtractor.extract("   ").text)
        assertEquals("", HtmlTextExtractor.extract("<html><body></body></html>").text)
    }

    @Test
    fun `page with no block elements extracts empty — tier predicate escalates`() {
        // The SPA-shell case: text lives in divs/spans, no block elements →
        // T1 yields nothing and decideAfterTier1 routes to the visible tab.
        val html = "<html><body><div><span>Some span text</span></div></body></html>"
        val page = HtmlTextExtractor.extract(html)
        assertEquals("", page.text)
        assertEquals(
            WebFetchTiers.TierDecision.EscalateToTab,
            WebFetchTiers.decideAfterTier1(200, page.text, html),
        )
    }

    @Test
    fun `title is extracted even when text is empty`() {
        val html = "<html><head><title>Gate page</title></head><body><div>only div text</div></body></html>"
        val page = HtmlTextExtractor.extract(html)
        assertEquals("Gate page", page.title)
        assertEquals("", page.text)
    }
}