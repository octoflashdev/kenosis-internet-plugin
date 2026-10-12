package hr.exel.kenosis_plugin_internet

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Process-wide [TabManager] holder — service + MainActivity share one
 * process, so a simple object is the whole transport (same pattern as
 * [CaptchaGate] and the fetch log). The WebView type is `Any` here because
 * the holder is referenced from both the binder threads (tool invocations)
 * and the UI thread (tab strip); production tabs carry `android.webkit.WebView`.
 */
object TabHolder {
    /**
     * Default: 10-tab FIFO. Tabs are created with explicit WebViews by the
     * service (main-thread `WebViewPageFetcher.createTabWebView`) and passed
     * in, so [TabManager.add] never hops threads under its lock. Dropped
     * tabs (FIFO / close / closeAll) destroy their WebView on the main
     * thread without waiting, so the callback is safe to run inside the
     * manager's lock.
     */
    @Volatile
    var manager: TabManager<Any> = TabManager(
        maxTabs = TabManager.MAX_TABS,
        onTabDropped = { tab ->
            val webView = tab.webView as? WebView
            if (webView != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        // Unparent BEFORE destroy — destroy() while still
                        // attached logs `WebView.destroy() called while
                        // WebView is still attached to window` and can leave
                        // the platform-view surface wedged (white).
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Exception) {
                        // Already destroyed — fine.
                    }
                }
            }
        },
    )

    /**
     * Keep a live tab's strip row + URL bar in sync (title / URL after
     * redirects, user clicks, or a page finish). Looks the tab up by its
     * WebView instance so neither the extract pipeline nor the platform-view
     * chrome reporter needs a tab id.
     */
    fun updateChromeFor(webView: WebView, title: String?, url: String?) {
        val id = manager.tabs.firstOrNull { it.webView == webView }?.id ?: return
        manager.updateChrome(id, title, url)
    }

    /** The tab strip payload the Flutter UI polls (id / title / url / focus). */
    fun tabsJson(): String {
        val sb = StringBuilder("[")
        val tabs = manager.tabs
        for ((i, tab) in tabs.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('{')
                .append("\"id\":").append(tab.id).append(',')
                .append("\"title\":").append(org.json.JSONObject.quote(tab.title)).append(',')
                .append("\"url\":").append(org.json.JSONObject.quote(tab.url)).append(',')
                .append("\"isForeground\":").append(tab.isForeground)
                .append('}')
        }
        return sb.append(']').toString()
    }
}
