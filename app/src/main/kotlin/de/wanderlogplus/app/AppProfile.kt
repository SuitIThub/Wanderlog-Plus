package de.wanderlogplus.app

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Which data set the app runs with: the normal one, or the debug one with its own server connection and its own
 * local data (database, sync state, attachments, way cache, reminders, imported reels). Nothing of one ever reaches
 * the other's server. The choice is read once per process; switching restarts the app ([switchTo]).
 */
object AppProfile {
    private const val PREFS = "app_profile"
    private const val KEY_DEBUG = "debug"

    @Volatile private var cached: Boolean? = null

    fun isDebug(context: Context): Boolean =
        cached ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEBUG, false).also { cached = it }

    /** [base] for the active profile: unchanged normally, "<base>-debug" in debug mode (preferences, folders). */
    fun scoped(context: Context, base: String): String = if (isDebug(context)) "$base-debug" else base

    /** Database file name of the active profile. */
    fun databaseName(context: Context): String = scoped(context, "wanderlog-plus") + ".db"

    /** Stores the new profile and restarts the app with it (the running process ends: open screens close). */
    fun switchTo(activity: Activity, debug: Boolean) {
        activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEBUG, debug).commit()
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) activity.startActivity(launch)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /** Tests: forget the cached choice. */
    internal fun resetForTests() { cached = null }
}
