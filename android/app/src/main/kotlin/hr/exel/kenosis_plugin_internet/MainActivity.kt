package hr.exel.kenosis_plugin_internet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * The plugin app's launcher activity. The service (InternetPluginService)
 * runs in the same process, so the UI can read the in-process fetch log
 * (InternetPluginService.snapshotFetchLogJson) directly — no binder round-trip
 * needed, just a MethodChannel hop.
 *
 * +75 the UI also polls the [CaptchaGate]: when a search engine bot-walls the
 * service, the gate names the engine + the exact blocked URL, and the UI shows
 * a warning banner whose Solve-now action loads that URL in a VISIBLE WebView
 * (the challenge is invisible in the headless fetcher — this is where the user
 * actually solves it). `captchaSolved` clears the gate + cancels the
 * [CaptchaNotifications] ping.
 */
class MainActivity : FlutterActivity() {

    private val channel = "kenosis_plugin/ui"

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
        requestNotificationPermissionIfNeeded()
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
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
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
                        val engine = call.argument<String>("engine")
                        if (engine != null) {
                            CaptchaGate.clear(engine)
                            CaptchaNotifications.cancel(this, engine)
                        }
                        // Check resolved — cookies back to denied (see
                        // CaptchaCookies; the earned jar stays dormant).
                        CaptchaCookies.setAllowed(false)
                        result.success(null)
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

    /** The pending check as the JSON the Flutter UI polls, or null (none /
     *  stale — a stale gate is dropped here so the UI never shows an
     *  expired challenge). The 2 s poll is also the cookie policy's
     *  self-healer: acceptance tracks the gate (CaptchaCookies), covering
     *  the TTL-staleness drop above, which bypasses clear(). */
    private fun captchaJson(): String? {
        val p = CaptchaGate.current(System.currentTimeMillis())
        CaptchaCookies.setAllowed(p != null)
        if (p == null) return null
        return JSONObject()
            .put("engine", p.engine)
            .put("url", p.url)
            .put("reason", p.reason)
            .put("sinceMs", p.sinceMs)
            .toString()
    }
}
