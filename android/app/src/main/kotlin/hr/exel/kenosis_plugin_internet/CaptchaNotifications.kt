package hr.exel.kenosis_plugin_internet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The "human check needed" system notification.
 *
 * One of the three +75 notification layers (in-plugin banner, this system
 * notification, and the actionable error envelope the host's model relays).
 * While an engine is bot-walled, EVERY search round re-arms [CaptchaGate] —
 * without a cooldown the user would get one notification per round, so a
 * SharedPreferences timestamp dedupes to one ping per engine per [COOLDOWN_MS].
 * A new ENGINE always notifies: a qwant ping does not cover a later duckduckgo
 * block.
 *
 * Tap → MainActivity (singleTop; the in-plugin banner with the solve-it
 * WebView sits at the top of that screen, so no deep-link plumbing is needed
 * — opening the app IS the deep link).
 *
 * POST_NOTIFICATIONS is a runtime permission on 33+; MainActivity requests it
 * once on open. A denied permission (or a pre-channel device quirk) degrades
 * to no-op here — the in-plugin banner still shows the check.
 */
object CaptchaNotifications {

    private const val CHANNEL_ID = "captcha_checks"
    private const val PREFS = "captcha_notifications"
    private const val KEY_LAST_NOTIFIED = "lastNotifiedMs."

    /** One ping per engine per window — the gate re-arms per search round. */
    const val COOLDOWN_MS = 6L * 60 * 60 * 1000

    /** Notification id/tag derived from the engine so [cancel] targets the
     *  right one when several engines blocked over time. */
    fun idFor(engine: String): Int = ("captcha-$engine").hashCode()

    /** Creates the channel (idempotent, cheap — safe to call per notify). */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Search engine checks",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Tells you when a search engine asks for a human " +
                "verification inside the Internet Search plugin"
        }
        manager.createNotificationChannel(channel)
    }

    /** Posts the check-needed notification for [engine], unless one already
     *  went out for it inside [COOLDOWN_MS] (or notifications are disabled).
     *  Called from the service when the gate arms; safe on binder threads. */
    fun notify(context: Context, engine: String, url: String, nowMs: Long) {
        if (!areEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_NOTIFIED + engine, 0L)
        if (nowMs - last < COOLDOWN_MS) return
        ensureChannel(context)
        val tapIntent = PendingIntent.getActivity(
            context,
            idFor(engine),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val engineLabel = engine.replaceFirstChar { it.uppercase() }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$engineLabel needs a human check")
            .setContentText(
                "The search engine is asking for a verification before it " +
                    "serves results. Tap to complete the check.",
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "The search engine is asking for a verification before " +
                            "it serves results. Tap to open the Internet Search " +
                            "plugin and complete the check, then search again " +
                            "in Kenosis AI.",
                    ),
            )
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify("captcha-$engine", idFor(engine), notification)
            prefs.edit().putLong(KEY_LAST_NOTIFIED + engine, nowMs).apply()
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post — banner
            // still covers it.
        }
    }

    /** Cancels the engine's notification (gate cleared — solved or expired). */
    fun cancel(context: Context, engine: String) {
        NotificationManagerCompat.from(context).cancel("captcha-$engine", idFor(engine))
    }

    private fun areEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }
}
