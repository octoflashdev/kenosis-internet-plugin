package hr.exel.kenosis_plugin_internet

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Flutter platform-view host for a [TabManager] tab's live [WebView].
 *
 * The ToS / WYSIWYG cut: the model reads exactly what this WebView displays,
 * and the user sees the SAME instance (not a mirror, not a second renderer).
 * The factory only borrows the WebView — its lifecycle stays with
 * [TabHolder] / `onTabDropped` (dispose detaches, never destroys).
 */
class TabViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val tabId = (args as? Map<*, *>)?.get("tabId") as? Int ?: -1
        return TabPlatformView(context, tabId)
    }
}

/**
 * Attaches tab [tabId]'s WebView into a Flutter surface. Title flows back via
 * [WebChromeClient]; URL chrome is the extract pipeline's WebViewClient
 * (never replaced here — see [installTitleReporting]).
 *
 * User clicks inside the page navigate THIS tab (Firefox left-click rule) —
 * the default WebView behavior; `shouldOverrideUrlLoading` is deliberately
 * not overridden.
 */
class TabPlatformView(
    private val context: Context,
    private val tabId: Int,
) : PlatformView {

    private val container = FrameLayout(context)

    init {
        attachWebView()
    }

    override fun getView(): View = container

    /** Detach only — [TabHolder.onTabDropped] owns destroy. */
    override fun dispose() {
        container.removeAllViews()
    }

    private fun attachWebView() {
        val webView = TabHolder.manager.tabs
            .firstOrNull { it.id == tabId }
            ?.webView as? WebView
            ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        installTitleReporting(webView)
        container.removeAllViews()
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * Title via [WebChromeClient] only. NEVER assign `webView.webViewClient`
     * here: [WebViewPageFetcher] owns that slot for the extract pipeline, and
     * replacing it mid-fetch (presentUi → Flutter attach racing web_search)
     * drops onPageFinished → the SERP walk times out on a page the user can
     * already see (2026-10-08). URL chrome rides the extract client's page
     * callbacks, which stay installed after the fetch.
     */
    private fun installTitleReporting(webView: WebView) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                TabHolder.updateChromeFor(view, title, view.url)
            }
        }
    }
}
