package de.wanderlogplus.app.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Tabelog's multilingual app for travellers (English, Chinese, Korean). */
const val TABELOG_APP = "com.kakaku.tabelog.tourists"

/** How a Tabelog page is opened, tried in order. */
sealed interface TabelogTarget {
    /** The restaurant opened in the multilingual app through its own deep link. */
    data class AppUrl(val url: String) : TabelogTarget

    /** The multilingual app's start screen. */
    data object AppStart : TabelogTarget

    data class Browser(val url: String) : TabelogTarget
}

/** With the app installed: the restaurant in the app, else the app itself, else the browser; without it: the browser. */
fun tabelogTargets(url: String, installed: Boolean): List<TabelogTarget> {
    if (url.isBlank()) return emptyList()
    if (!installed) return listOf(TabelogTarget.Browser(url))
    return listOfNotNull(tabelogAppLink(url)?.let { TabelogTarget.AppUrl(it) }, TabelogTarget.AppStart, TabelogTarget.Browser(url))
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

/**
 * The tourist app's own link to a restaurant ("tabelog-tourists://rstdtl/26032368/top/"), as Tabelog's English pages
 * use it in their "Get the Tabelog app" button; null when [url] is no restaurant page. The app does not take web addresses.
 */
fun tabelogAppLink(url: String): String? =
    de.wanderlogplus.shared.model.canonicalTabelogUrl(url)?.trimEnd('/')?.substringAfterLast('/')?.let { "tabelog-tourists://rstdtl/$it/top/" }
