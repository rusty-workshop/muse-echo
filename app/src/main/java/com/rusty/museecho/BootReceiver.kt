package com.rusty.museecho

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rusty.museecho.gadget.GadgetService

/** Starts the gadget connection at boot, so the Echo is back on Muse after a power cut. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            GadgetService.start(context)
        }
    }
}
