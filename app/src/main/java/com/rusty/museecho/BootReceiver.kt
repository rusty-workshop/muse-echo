package com.rusty.museecho

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rusty.museecho.gadget.GadgetService

/** Starts the gadget connection at boot, so the Echo is back on Muse after a power cut. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
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
}
