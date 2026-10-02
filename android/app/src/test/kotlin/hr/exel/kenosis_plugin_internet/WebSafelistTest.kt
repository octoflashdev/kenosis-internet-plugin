package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InternetPluginService.stripToWebFragment] — the jsoup
 * `Safelist` strip that produces the reader sidecar HTML from a saved web
 * page. The reader passes this fragment through UN-ESCAPED, so the allowlist
 * is the only thing standing between a fetched page and the WebView DOM: it
 * must keep structural/formatting tags (`<b>`/`<br>`/`<p>`/headings/lists) and
 * drop everything that could fetch remote resources, run script, or leak
 * attributes (`<img>`/`<script>`/`<style>`/`<a>`/`<div>`/`<span>`/all attrs).
 *
 * Run: `cd plugins/internet/android && ./gradlew :app:testDebugUnitTest`.
 * Pure JVM (jsoup is on the test classpath via the main `implementation` dep);
 * no Android calls — [InternetPluginService.Companion] initialization is
 * JVM-safe (proven by [ShouldFallbackTest] accessing the same companion).
 */
class WebSafelistTest {

    private fun strip(html: String): String =
        InternetPluginService.stripToWebFragment(html, "https://example.com/page")

    @Test
    fun `formatting and structure tags are preserved`() {
        val out = strip("<p>Hello <b>world</b></p><h2>Title</h2><ul><li>one</li></ul>")
        assertTrue("kept <p>", out.contains("<p>"))
        assertTrue("kept <b>", out.contains("<b>"))
        assertTrue("kept <h2>", out.contains("<h2>"))
        assertTrue("kept <ul>", out.contains("<ul>"))
        assertTrue("kept <li>", out.contains("<li>"))
        assertTrue("kept text", out.contains("Hello") && out.contains("world"))
    }

    @Test
    fun `script tag and its body are dropped`() {
        val out = strip("<p>safe</p><script>alert('xss')</script>")
        assertFalse("no <script>", out.contains("<script"))
        assertFalse("no script body leaked", out.contains("alert"))
        assertTrue("surrounding text kept", out.contains("safe"))
    }

    @Test
    fun `style tag and img are dropped`() {
        val out = strip("<style>.x{color:red}</style><img src=\"https://e/x.png\" alt=\"pic\">")
        assertFalse("no <style>", out.contains("<style"))
        assertFalse("no <img", out.contains("<img"))
        assertFalse("no remote src", out.contains("x.png"))
        // The CSS text inside <style> is data, not content — must NOT leak.
        assertFalse("no css body leaked", out.contains("color:red"))
    }

    @Test
    fun `anchor tag dropped but its text kept`() {
        // No url_launcher in the app — links render as plain text. The <a> tag
        // and its href must go; the link label must survive.
        val out = strip("<a href=\"https://evil.example/path\">read more</a>")
        assertFalse("no <a", out.contains("<a"))
        assertFalse("no href", out.contains("href"))
        assertFalse("no url leaked", out.contains("evil.example"))
        assertTrue("link label kept", out.contains("read more"))
    }

    @Test
    fun `div and span dropped but their text kept`() {
        val out = strip("<div class=\"article\"><span>inner</span> text</div>")
        assertFalse("no <div", out.contains("<div"))
        assertFalse("no <span", out.contains("<span"))
        assertTrue("text kept", out.contains("inner") && out.contains("text"))
    }

    @Test
    fun `all attributes are stripped`() {
        // Safelist.none()+addTags adds NO attributes — so class/style/id/href
        // all vanish, even on allowed tags.
        val out = strip("<p style=\"color:red\" class=\"lead\" id=\"p1\" onclick=\"x()\">Hi</p>")
        assertTrue("kept <p>", out.contains("<p>"))
        assertFalse("no style attr", out.contains("style="))
        assertFalse("no class attr", out.contains("class="))
        assertFalse("no id attr", out.contains("id="))
        assertFalse("no onclick", out.contains("onclick"))
        assertTrue("text kept", out.contains("Hi"))
    }

    @Test
    fun `br and hr are preserved`() {
        val out = strip("line one<br>line two<hr>after")
        assertTrue("kept <br>", out.contains("<br>"))
        assertTrue("kept <hr>", out.contains("<hr>"))
    }

    @Test
    fun `malformed input yields empty fragment not a crash`() {
        // stripToWebFragment catches Jsoup.clean exceptions → "". The reader
        // then falls back to the escaped plain text. A bad fragment must never
        // kill the fetch (the plain .txt is still returned).
        // Jsoup.clean is very tolerant, so feed it a huge unbalanced blob and
        // assert only "no crash + no <script slipped through".
        val out = strip("<p>" + "x".repeat(20000) + "<script>bad</script>")
        assertFalse("no script in output", out.contains("<script"))
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals("", strip(""))
    }
}