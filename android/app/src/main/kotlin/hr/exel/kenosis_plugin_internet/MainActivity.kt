package hr.exel.kenosis_plugin_internet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * The plugin app's launcher activity + tab UI host.
 *
 * The service (InternetPluginService) runs in the same process, so the UI
 * can read the in-process fetch log (InternetPluginService.snapshotFetchLogJson)
 * directly — no binder round-trip needed, just a MethodChannel hop.
 *
 * Tabs: [TabManager] holds the visible search/fetch tabs (SERP proof + the
 * opened page). The Flutter shell wraps chrome (drawer / About / Settings);
 * the live WebView is the tab. Settings channel methods `getSettings` /
 * `setSettings` expose [BrowserSettings] (Load images / Enable JavaScript /
 * Wipe on close).
 *
 * Captcha: the UI polls [CaptchaGate] and offers a visible solve surface.
 * `captchaSolved` is a "user finished" ping — it dismisses the notification;
 * it must NOT auto-rearm cookies for scraping (the human solved it in a
 * visible tab; the page just loads).
 */
class MainActivity : FlutterActivity() {

    private val channel = "kenosis_plugin/ui"

    /** Visible tab strip — shared with the service via process-wide holder. */
    val tabManager = TabHolder.manager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The URL list reads the service's COMPANION state directly — no
        // binder, no service start. So a UI-only launch (fresh process,
        // service not bound by the host) must hydrate the log from disk HERE:
        // initFetchLogPersistence was service-onCreate-only, and the app
        // relaunch after a force-stop showed "Requested URLs (0)" with rows
        // sitting in fetch_log.json (verified on-device 2026-09-28).
        // Idempotent + synchronized — safe alongside the service's own call.
        InternetPluginService.initFetchLogPersistence(this)
        BrowserSettings.init(this)
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        // EXIT privacy only — never an activity-lifecycle tax. presentUi and
        // config recreates destroy/recreate this activity while the service
        // still owns the tab WebViews; an unconditional wipe here is what
        // emptied the strip mid-web_search (2026-10-08 "plugin lost tabs").
        if (BrowserSettings.shouldWipeOnDestroy(
                isFinishing = isFinishing,
                isChangingConfigurations = isChangingConfigurations,
                invokeInFlight = InternetPluginService.isInvokeInFlight(),
            )
        ) {
            wipeBrowserState()
        }
        super.onDestroy()
    }

    /** Wipe-on-close: tabs + cookies + site storage + cache. Cache is cleared
     *  on the live tab WebViews before destroy — a throwaway `WebView(this)`
     *  created inside onDestroy is a leak/crash pattern and never saw the
     *  tab cookies anyway. */
    private fun wipeBrowserState() {
        runCatching {
            for (tab in tabManager.tabs) {
                (tab.webView as? WebView)?.clearCache(true)
            }
        }
        runCatching { tabManager.closeAll() }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    /** One-time POST_NOTIFICATIONS request (33+): the captcha ping needs it.
     *  Asked only while opening the plugin app — never from the background
     *  service, which Android would ignore. A denial silently degrades to
     *  banner-only notification. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), 1)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Live tab surface: the SAME WebView instances the model extracts
        // from (TabHolder) — WYSIWYG for the user and the tool.
        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("kenosis_plugin/tab_view", TabViewFactory())
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getTabs" -> {
                        result.success(TabHolder.tabsJson())
                    }
                    "setActiveTab" -> {
                        val id = call.argument<Int>("id") ?: -1
                        result.success(TabHolder.manager.setActive(id) != null)
                    }
                    "closeTab" -> {
                        val id = call.argument<Int>("id") ?: -1
                        result.success(TabHolder.manager.close(id) != null)
                    }
                    // closeAllTabs channel method DELETED (dead code — Flutter
                    // never calls it). closeAll() stays for wipe-on-close.
                    "getFetchLog" -> {
                        result.success(InternetPluginService.snapshotFetchLogJson())
                    }
                    "clearFetchLog" -> {
                        // UI Delete-all: wipe the log in RAM + on disk in one
                        // step (the persisted EMPTY array — a process restart
                        // rehydrating from fetch_log.json can't resurrect it).
                        InternetPluginService.clearFetchLog()
                        result.success(null)
                    }
                    "getCaptcha" -> {
                        result.success(captchaJson())
                    }
                    "captchaSolved" -> {
                        // "User finished" ping only — dismiss the notification.
                        // NO cookie re-arm for scraping: the human solved it
                        // in a visible tab; the page just loads and extract
                        // continues (or the model retries on the coach copy).
                        val engine = call.argument<String>("engine")
                        if (engine != null) {
                            CaptchaGate.clear(engine)
                            CaptchaNotifications.cancel(this, engine)
                        }
                        result.success(null)
                    }
                    "getSettings" -> {
                        result.success(BrowserSettings.toJson())
                    }
                    "setSettings" -> {
                        val loadImages = call.argument<Boolean>("loadImages")
                        val javaScript = call.argument<Boolean>("javaScript")
                        val wipeOnClose = call.argument<Boolean>("wipeOnClose")
                        BrowserSettings.apply(loadImages, javaScript, wipeOnClose)
                        BrowserSettings.persist(this)
                        applyBrowserSettingsToTabs()
                        result.success(BrowserSettings.toJson())
                    }
                    "openExternalUrl" -> {
                        // Play Store hand-off (Get Kenosis AI / About). The
                        // plugin holds INTERNET — unlike the offline host —
                        // so a user-initiated store link is sanctioned here
                        // (always behind the Dart-side confirm alert).
                        // ACTION_VIEW: the Play app intercepts its own deep
                        // links; startActivity is not subject to package
                        // visibility, so no <queries> entry is needed.
                        val url = call.argument<String>("url")
                        if (url.isNullOrBlank()) {
                            result.error("badArgs", "url missing", null)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            result.success(runCatching { startActivity(intent) }.isSuccess)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** Push Load-images / Enable-JavaScript onto every live tab WebView so a
     *  settings change takes effect without waiting for the next open. */
    private fun applyBrowserSettingsToTabs() {
        runOnUiThread {
            for (tab in tabManager.tabs) {
                val webView = tab.webView as? WebView ?: continue
                webView.settings.javaScriptEnabled = BrowserSettings.javaScript
                webView.settings.blockNetworkImage = !BrowserSettings.loadImages
            }
        }
    }

    /** The pending check as the JSON the Flutter UI polls, or null (none /
     *  stale — a stale gate is dropped here so the UI never shows an
     *  expired challenge). NO cookie re-arm: the human solves the check in
     *  a visible tab, and `captchaSolved` only dismisses the notification. */
    private fun captchaJson(): String? {
        val p = CaptchaGate.current(System.currentTimeMillis())
        if (p == null) return null
        return JSONObject()
            .put("engine", p.engine)
            .put("url", p.url)
            .put("reason", p.reason)
            .put("sinceMs", p.sinceMs)
            .toString()
    }
}
