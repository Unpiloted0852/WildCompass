package io.github.unpiloted0852.wildcompass

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Brings the app back after it has updated itself. Installing an update closes the app, and
 * Android then tells the new version that it has replaced the old one.
 *
 * Up to Android 9 the app simply opens again. From Android 10 on, the system does not let
 * an app open itself from the background, so the nearest thing is offered instead: a
 * notification that reopens the app when tapped.
 *
 * Only an update started from the in-app pill does this; one installed some other way
 * (a file manager, adb, a store) leaves the app closed and silent.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val asked = prefs.getLong(KEY, 0L)
        prefs.edit().remove(KEY).apply()
        if (System.currentTimeMillis() - asked > MAX_AGE_MS) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            context.startActivity(launch)
        } else if (canNotify(context)) {
            notifyUpdated(context, launch)
        }
    }

    private fun notifyUpdated(context: Context, launch: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation_arrow)
            .setContentTitle("WildCompass ${BuildConfig.VERSION_NAME} is installed")
            .setContentText("Tap to open it again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val PREFS = "update"
        private const val KEY = "askedAt"
        private const val MAX_AGE_MS = 10 * 60 * 1000L
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 1

        /** Call just before handing an update to the installer. */
        fun expectUpdate(context: Context) {
            // commit(), not apply(): the process may be killed before an async write lands.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY, System.currentTimeMillis()).commit()
        }

        /** Whether the "tap to reopen" notification could be shown after an update. */
        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

        /** True where the app cannot reopen itself and needs the notification to offer it. */
        val needsNotification: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
