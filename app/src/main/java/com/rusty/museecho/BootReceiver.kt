package com.rusty.museecho

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rusty.museecho.gadget.GadgetService

/**
 * Starts the gadget connection at boot (so the Echo is back on Muse after a
 * power cut), after an update, and from the keep-alive alarm if Android ever
 * killed the service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val keepAlive = intent.action == ACTION_KEEPALIVE
        if (keepAlive && GadgetService.instance != null) return
        if (keepAlive || intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // With the wake word on, start through the activity: Android 11 only
            // lets a service started from the foreground use the microphone.
            if (com.rusty.museecho.gadget.Prefs(context).wakeWordEnabled) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_BOOT, true))
            } else {
                GadgetService.start(context)
            }
        }
    }

    companion object {
        const val ACTION_KEEPALIVE = "com.rusty.museecho.KEEPALIVE"

        /** A 15-minute inexact alarm that restarts the service if it's gone. */
        fun scheduleKeepAlive(context: Context) {
            val pending = android.app.PendingIntent.getBroadcast(context, 1,
                Intent(context, BootReceiver::class.java).setAction(ACTION_KEEPALIVE),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(android.app.AlarmManager::class.java).setInexactRepeating(
                android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + INTERVAL,
                INTERVAL, pending)
        }

        private const val INTERVAL = 15 * 60_000L
    }
}
