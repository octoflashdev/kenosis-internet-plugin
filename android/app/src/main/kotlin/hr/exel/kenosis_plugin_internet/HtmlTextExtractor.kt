package hr.exel.kenosis_plugin_internet

import org.jsoup.Jsoup
import org.jsoup.select.Elements

/**
 * Tier-1 text extraction: server HTML → the same plain-text shape the
 * visible-tab extractor serves (tier plan: anonymous-first-tiered-web-fetch).
 *
 * Mirrors the semantic of [WebViewPageFetcher.DOM_WALK_JS] without the
 * WebView: strip non-content elements, then emit ONE LINE per BLOCK element
 * (h1-h6, p, li, dt/dd, th/td, blockquote, pre, figcaption), whitespace
 * collapsed — what a reader-mode client sees. A page whose text lives in
 * div/span containers only (no block elements) extracts nothing → the tier
 * predicate escalates to the visible tab, exactly as designed for SPAs.
 *
 * Pure JVM (Jsoup has no Android types) so [HtmlTextExtractorTest] pins the
 * fixture → expected-lines round trip: script/style stripped, one line per
 * li/th/p, nested block containers emitted once (leafmost only).
 *
 * Caps mirror the extractor's philosophy: a per-line trim so one runaway
 * element cannot dominate the extract, and a total cap at the same 24 KB
 * budget `DOM_WALK_JS` truncates to.
 */
object HtmlTextExtractor {

    /**
     * Per-line cap (MAX_ITEM_CHARS-style): a single block element longer
     * than this is trimmed, not dropped — one minified JS blob or a giant
     * <pre> must not starve the rest of the page's lines.
     */
    const val MAX_LINE_CHARS = 2000

    /** Total extract cap — the same budget DOM_WALK_JS truncates to. */
    const val MAX_TOTAL_CHARS = 24576

    /** The block elements the walk emits one line each for. */
    const val BLOCK_SELECTOR =
        "h1,h2,h3,h4,h5,h6,p,li,dt,dd,th,td,blockquote,pre,figcaption"

    /** Non-content elements: removed BEFORE the walk (JS payload text,
     *  template innards and noscript fallbacks are never content). */
    private val STRIP_SELECTOR = "script,style,noscript,iframe,template"

    /** Extracted page: the <title> and the newline-joined block lines. */
    data class ExtractedPage(val title: String, val text: String)

    /**
     * Parse [rawHtml] and emit the block-walk text ("" when the HTML yields
     * no block elements — the tier predicate treats that as thin).
     * [title] is the <title> tag, trimmed (the tab extractor's document.title
     * equivalent — never used as text; a blank page keeps a false title and
     * the predicate keys on the TEXT only).
     */
    fun extract(rawHtml: String): ExtractedPage {
        if (rawHtml.isBlank()) return ExtractedPage("", "")
        val doc = Jsoup.parse(rawHtml)
        val title = doc.title().trim()
        doc.select(STRIP_SELECTOR).remove()
        val body = doc.body() ?: return ExtractedPage(title, "")
        // Leafmost blocks only: an element that CONTAINS another block
        // element (ul around li, blockquote around p, td around p) is NOT
        // emitted itself — its text would duplicate every descendant line.
        // NOTE: jsoup's Element.select is SELF-INCLUSIVE (matches el itself
        // when el matches the query), so the descendant check goes through
        // children().select — a direct-child block matches itself there, and
        // deeper ones match through it. Element.select(filter { it !== el })
        // would work too but children().select states the intent directly.
        val sb = StringBuilder()
        val blocks: Elements = body.select(BLOCK_SELECTOR)
        for (el in blocks) {
            if (!el.children().select(BLOCK_SELECTOR).isEmpty()) continue
            val line = el.text().trim()
            if (line.isEmpty()) continue
            val trimmed =
                if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
            if (sb.isNotEmpty()) {
                if (sb.length >= MAX_TOTAL_CHARS) break
                sb.append('\n')
            }
            if (sb.length + trimmed.length > MAX_TOTAL_CHARS) {
                val room = MAX_TOTAL_CHARS - sb.length
                if (room > 0) sb.append(trimmed, 0, room)
                break
            }
            sb.append(trimmed)
        }
        return ExtractedPage(title, sb.toString())
    }
}