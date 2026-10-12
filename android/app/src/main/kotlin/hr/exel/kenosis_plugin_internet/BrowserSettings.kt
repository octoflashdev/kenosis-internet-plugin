package hr.exel.kenosis_plugin_internet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The plugin mini-browser's three user knobs (plan: visible tab model).
 *
 *  - [loadImages] (default ON) — a real browser loads images; blocking them
 *    is a fetcher tell. The tab shows the page as the site serves it.
 *  - [javaScript] (default ON) — SPAs and captcha challenges need it; the
 *    settings UI says so. Turning it off is a user choice, not a ToS lever.
 *  - [wipeOnClose] (default ON) — on plugin destroy, erase cookies, site
 *    storage and cache. The tab browser is a tool, not an identity.
 *
 * Process-wide (service + MainActivity share one process), like
 * [CaptchaGate]. Persisted so a relaunch keeps the user's choice.
 */
object BrowserSettings {

    private const val PREFS = "kenosis_plugin_browser"
    private const val KEY_LOAD_IMAGES = "loadImages"
    private const val KEY_JAVA_SCRIPT = "javaScript"
    private const val KEY_WIPE_ON_CLOSE = "wipeOnClose"

    /** Pure defaults — the unit-test pin and the pre-persist state. */
    const val DEFAULT_LOAD_IMAGES = true
    const val DEFAULT_JAVA_SCRIPT = true
    const val DEFAULT_WIPE_ON_CLOSE = true

    @Volatile
    var loadImages: Boolean = DEFAULT_LOAD_IMAGES

    @Volatile
    var javaScript: Boolean = DEFAULT_JAVA_SCRIPT

    @Volatile
    var wipeOnClose: Boolean = DEFAULT_WIPE_ON_CLOSE

    /** Idempotent — safe from both the service's and the activity's onCreate. */
    @Synchronized
    fun init(context: Context) {
        val prefs = prefs(context)
        loadImages = prefs.getBoolean(KEY_LOAD_IMAGES, DEFAULT_LOAD_IMAGES)
        javaScript = prefs.getBoolean(KEY_JAVA_SCRIPT, DEFAULT_JAVA_SCRIPT)
        wipeOnClose = prefs.getBoolean(KEY_WIPE_ON_CLOSE, DEFAULT_WIPE_ON_CLOSE)
    }

    /** Apply a `setSettings` payload. Missing keys leave the current value. */
    @Synchronized
    fun apply(loadImages: Boolean?, javaScript: Boolean?, wipeOnClose: Boolean?) {
        this.loadImages = loadImages ?: this.loadImages
        this.javaScript = javaScript ?: this.javaScript
        this.wipeOnClose = wipeOnClose ?: this.wipeOnClose
    }

    @Synchronized
    fun persist(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_LOAD_IMAGES, loadImages)
            .putBoolean(KEY_JAVA_SCRIPT, javaScript)
            .putBoolean(KEY_WIPE_ON_CLOSE, wipeOnClose)
            .apply()
    }

    /** The `getSettings` payload the Flutter UI polls. */
    fun toJson(): String =
        JSONObject()
            .put("loadImages", loadImages)
            .put("javaScript", javaScript)
            .put("wipeOnClose", wipeOnClose)
            .toString()

    /**
     * Pure: may this activity destroy wipe tabs + cookies + storage + cache?
     *
     * Wipe-on-close is an EXIT privacy action, not an activity-lifecycle tax.
     * `presentUi` (`FLAG_ACTIVITY_REORDER_TO_FRONT`) and config recreates
     * destroy/recreate [MainActivity] while the service still owns the live
     * tab WebViews — wiping there emptied the strip mid-`web_search` and the
     * killed WebViews logged fetch errors (2026-10-08: "plugin lost tabs,
     * plugin showed error" while the host still answered).
     *
     * Wipe only when the user really leaves (activity finishing), it is not
     * a configuration recreate, and no tool invoke still needs those tabs.
     */
    fun shouldWipeOnDestroy(
        isFinishing: Boolean,
        isChangingConfigurations: Boolean,
        invokeInFlight: Boolean,
        wipeOnClose: Boolean = this.wipeOnClose,
    ): Boolean {
        if (!wipeOnClose) return false
        if (isChangingConfigurations) return false
        if (!isFinishing) return false
        if (invokeInFlight) return false
        return true
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
