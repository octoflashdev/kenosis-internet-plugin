package hr.exel.kenosis_plugin_internet

import java.net.URLEncoder

/**
 * Visible tab-strip bookkeeping for the plugin mini-browser.
 *
 * The plugin is an auditable AI-driven browser: `web_search` opens a SERP
 * tab (proof of what was asked) plus an "I'm Lucky" tab (the first openable
 * result), `browser_fetch` opens the requested page. The user can inspect
 * every tab at any time; nothing runs in a hidden WebView.
 *
 * Pure JVM (no Android types in the bookkeeping) so [TabManagerTest] pins
 * cap / FIFO / active / search-pair / closeAll. The live `android.webkit.WebView`
 * for a tab is owned by the host view ([W] — `WebView` in production, a
 * dummy in tests) and rides along on each [Tab] so the strip can show it.
 *
 * Cap is [MAX_TABS] with FIFO rotation: the 11th open drops the oldest.
 * A search round uses two tabs (SERP + Lucky); a fetch uses one.
 */
class TabManager<W : Any>(
    private val maxTabs: Int = MAX_TABS,
    /** Invoked when a tab leaves the strip (FIFO drop, close, closeAll) so
     *  the caller can destroy its WebView. Must not re-enter the manager. */
    private val onTabDropped: (Tab<W>) -> Unit = {},
) {

    companion object {
        const val MAX_TABS = 10

        /**
         * Pure: the engine's SERP URL for [query]. NO IFL auto-navigate —
         * the Google backdoor is gone; "I'm Lucky" means *visibly* opening
         * the 1st SERP link in its own tab.
         */
        fun serpUrl(query: String, engine: String): String {
            val q = URLEncoder.encode(query, "UTF-8")
            return when (engine.lowercase()) {
                "duckduckgo" -> "https://html.duckduckgo.com/html/?q=$q"
                "qwant" -> "https://www.qwant.com/?q=$q&t=web"
                // udm=14 = the "Web" tab: classic result anchors, less AI-Mode
                // chrome in front of them. Same /search endpoint a user gets.
                else -> "https://www.google.com/search?q=$q&udm=14"
            }
        }

        /** Pure: engine label used on the tab title chip. */
        fun engineLabel(engine: String): String = when (engine.lowercase()) {
            "duckduckgo" -> "DDG"
            "qwant" -> "Qwant"
            else -> "Google"
        }

        /**
         * Engine hosts (plan: anonymous-first-tiered-web-fetch) — the hosts
         * [serpUrl] opens, plus the engine-family hosts a SERP's own links /
         * CDN chrome live on. The tier ladder routes ANY of these hosts away
         * from Tier 1 (see [isEngineUrl]): an anonymous client fetching an
         * engine page is the SERP scrape the ToS rule bans.
         */
        val ENGINE_HOSTS = listOf(
            "google.com",
            "duckduckgo.com",
            "qwant.com",
        )

        /**
         * Pure, unit-testable: is [url] an ENGINE URL — a search engine's own
         * page (SERP, nav, /search, accounts) or an OUTBOUND RESULT
         * REDIRECTOR on an engine host (Google `/url?…` / `/goto?url=CAES-…`,
         * DuckDuckGo `/l/?uddg=…`, Qwant `/redirect?…`)?
         *
         * The SINGLE SOURCE of engine-host knowledge for the tier ladder: the
         * tier client ([WebFetchTiers]) consumes this to send engine URLs
         * straight to Tier 2 — a Google /goto redirector OkHttp-fetched by an
         * anonymous client IS the SERP scrape the ToS rule bans, and since
         * 2026-10-07 Google serves organic results as goto-token URLs, many
         * web_search candidate URLs ARE engine URLs. Never an error anywhere —
         * engine URLs are WebView-only, exactly today's flow.
         *
         * Broad on purpose: any host containing "google" (national TLDs like
         * google.de, accounts.google.com) and the SERP-riding CDN family from
         * [InternetPluginService.isEngineOwnUrl] (doubleclick / gstatic /
         * googleapis / ggpht) never meet the anonymous client. A non-engine
         * host whose name merely contains "google" loses nothing — it gets the
         * visible-tab tier, which reads the same page.
         */
        fun isEngineUrl(url: String): Boolean {
            val uri = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
            val host = uri.host?.lowercase() ?: return false
            if (ENGINE_HOSTS.any { host == it || host.endsWith(".$it") }) return true
            if (host.contains("google") ||
                host.endsWith(".doubleclick.net") ||
                host.endsWith(".gstatic.com") ||
                host.endsWith(".googleapis.com") ||
                host.endsWith(".ggpht.com")
            ) {
                return true
            }
            // Engine REDIRECTOR paths on subdomains the host match above
            // somehow missed (defense in depth — the redirect family
            // [InternetPluginService.resolveResultTarget] unwraps).
            val path = uri.path ?: ""
            return (host.endsWith("duckduckgo.com") && path.contains("/l")) ||
                (host.endsWith("qwant.com") && path.contains("redirect"))
        }

        /**
         * Pure, unit-testable: is [url] one of the engine's OUTBOUND RESULT
         * REDIRECTORS? Google `/url?…` (plaintext interstitial) and
         * `/goto?url=CAES-…` (2026-10-07 CDP: the encrypted-token form Google
         * now serves for organic results — NOT client-unwrappable);
         * DuckDuckGo `/l/?uddg=…`; Qwant `/redirect?…`.
         * [InternetPluginService.resolveResultTarget] attempts the unwrap on
         * these paths; the tier ladder's skipTier1 exempts them (T1 carve-out
         * — see [WebFetchTiers.skipTier1]). Public for the unit-test pins.
         */
        fun isEngineRedirector(url: String): Boolean {
            val uri = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
            val host = uri.host?.lowercase() ?: return false
            val path = uri.path ?: ""
            val isDdgRedirect = host.endsWith("duckduckgo.com") && path.contains("/l")
            val isGoogleRedirect = host.contains("google") &&
                (path.startsWith("/url") || path.startsWith("/goto"))
            val isQwantRedirect = host.endsWith("qwant.com") && path.contains("redirect")
            return isDdgRedirect || isGoogleRedirect || isQwantRedirect
        }
    }

    data class Tab<W : Any>(
        val id: Int,
        val title: String,
        val url: String,
        /** The tab the user is looking at. Search: the SERP until a result
         *  opens, then the Lucky tab. Fetch: the fetched page. */
        val isForeground: Boolean,
        /** `android.webkit.WebView` in production; null in pure tests. */
        val webView: W?,
    )

    private var nextId = 1
    private val _tabs = ArrayList<Tab<W>>()

    val tabs: List<Tab<W>>
        get() = synchronized(this) { _tabs.toList() }

    val activeTab: Tab<W>?
        get() = synchronized(this) {
            _tabs.firstOrNull { it.isForeground } ?: _tabs.lastOrNull()
        }

    /**
     * `web_search``s pair: a foreground SERP tab (the only tab with content
     * until a result is known — the proof of what was asked) + a background
     * Lucky slot (the 1st openable link once the pick is known; the caller
     * navigates + [setActive]s it via [navigate]).
     *
     * The Lucky WebView is a NEW instance and is NOT loaded here — giving it
     * the SERP URL in metadata without a load left a blank surface in front
     * of the real results (2026-10-07 on-device: "opened tab was empty,
     * hidden tab was the Google page"). Focus follows content.
     */
    fun openSearchTabs(
        query: String,
        engine: String,
        serpWebView: W? = null,
        luckyWebView: W? = null,
    ): Pair<Tab<W>, Tab<W>> =
        synchronized(this) {
            val label = engineLabel(engine)
            val serp = add(
                title = "$label · $query",
                url = serpUrl(query, engine),
                foreground = true,
                webView = serpWebView,
            )
            val lucky = add(
                title = query,
                url = "",
                foreground = false,
                webView = luckyWebView,
            )
            serp to lucky
        }

    /** `browser_fetch`'s single tab (foreground — that's the page asked for). */
    fun openUrlTab(url: String, title: String = url, webView: W? = null): Tab<W> =
        synchronized(this) { add(title = title, url = url, foreground = true, webView = webView) }

    /** Move an existing tab to [url] (the Lucky tab after link-pick). */
    fun navigate(id: Int, url: String, title: String = url): Tab<W>? =
        synchronized(this) {
            val i = _tabs.indexOfFirst { it.id == id }
            if (i < 0) return null
            val updated = _tabs[i].copy(title = title, url = url)
            _tabs[i] = updated
            updated
        }

    /** Update title/URL as the WebView reports them (redirects, page title). */
    fun updateChrome(id: Int, title: String?, url: String?): Tab<W>? =
        synchronized(this) {
            val i = _tabs.indexOfFirst { it.id == id }
            if (i < 0) return null
            val cur = _tabs[i]
            val updated = cur.copy(
                title = title?.takeIf { it.isNotBlank() } ?: cur.title,
                url = url?.takeIf { it.isNotBlank() } ?: cur.url,
            )
            _tabs[i] = updated
            updated
        }

    fun setActive(id: Int): Tab<W>? = synchronized(this) {
        var hit: Tab<W>? = null
        for (i in _tabs.indices) {
            val want = _tabs[i].id == id
            if (_tabs[i].isForeground != want) {
                _tabs[i] = _tabs[i].copy(isForeground = want)
            }
            if (want) hit = _tabs[i]
        }
        hit
    }

    fun close(id: Int): Tab<W>? = synchronized(this) {
        val i = _tabs.indexOfFirst { it.id == id }
        if (i < 0) return null
        val removed = _tabs.removeAt(i)
        // Keep a foreground tab after a close (the newest remaining).
        if (removed.isForeground && _tabs.isNotEmpty()) {
            val last = _tabs.size - 1
            _tabs[last] = _tabs[last].copy(isForeground = true)
        }
        onTabDropped(removed)
        removed
    }

    /** Wipe-on-close / settings reset: drop every tab. Returns the dropped
     *  tabs so the caller can destroy their WebViews. */
    fun closeAll(): List<Tab<W>> = synchronized(this) {
        val dropped = _tabs.toList()
        _tabs.clear()
        for (tab in dropped) onTabDropped(tab)
        dropped
    }

    /** Insert with FIFO: an open past [maxTabs] drops the oldest tab. */
    private fun add(
        title: String,
        url: String,
        foreground: Boolean,
        webView: W? = null,
    ): Tab<W> {
        if (foreground) {
            for (i in _tabs.indices) {
                if (_tabs[i].isForeground) {
                    _tabs[i] = _tabs[i].copy(isForeground = false)
                }
            }
        }
        while (_tabs.size >= maxTabs) {
            onTabDropped(_tabs.removeAt(0))
        }
        val tab = Tab(
            id = nextId++,
            title = title,
            url = url,
            isForeground = foreground,
            webView = webView,
        )
        _tabs.add(tab)
        return tab
    }
}
