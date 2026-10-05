package com.rusty.museecho.integrations

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Launching Sofa on the Echo, in a given mode. */
object Apps {
    const val SOFA = "com.rusty.sofa"
    const val SOFA_ROUTE_EXTRA = "com.rusty.sofa.extra.ROUTE"

    val SOFA_MODES = listOf("home", "school", "tv", "coding", "lmstudio", "ambient", "settings")

    fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun openSofa(context: Context, mode: String) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(SOFA, "$SOFA.MainActivity"))
                .putExtra(SOFA_ROUTE_EXTRA, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}
