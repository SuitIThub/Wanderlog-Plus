package de.wanderlogplus.app.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Tabelog's multilingual app for travellers (English, Chinese, Korean). */
const val TABELOG_APP = "com.kakaku.tabelog.tourists"

/** How a Tabelog page is opened, tried in order. */
sealed interface TabelogTarget {
    /** The page handed to the multilingual app (works when the app takes Tabelog links). */
    data class AppUrl(val url: String) : TabelogTarget

    /** The multilingual app's start screen. */
    data object AppStart : TabelogTarget

    data class Browser(val url: String) : TabelogTarget
}

/** With the app installed: the page in the app, else the app itself, else the browser; without it: the browser. */
fun tabelogTargets(url: String, installed: Boolean): List<TabelogTarget> {
    if (url.isBlank()) return emptyList()
    return if (installed) listOf(TabelogTarget.AppUrl(url), TabelogTarget.AppStart, TabelogTarget.Browser(url)) else listOf(TabelogTarget.Browser(url))
}

/** Opens [url] (best the English page) the first way that works; false when nothing could. */
fun openTabelog(context: Context, url: String): Boolean {
    val pm = context.packageManager
    val launch = pm.getLaunchIntentForPackage(TABELOG_APP)
    for (t in tabelogTargets(url, launch != null)) {
        val intent = when (t) {
            is TabelogTarget.AppUrl -> Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).setPackage(TABELOG_APP)
            TabelogTarget.AppStart -> launch ?: continue
            is TabelogTarget.Browser -> Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // next way
        }
    }
    return false
}
