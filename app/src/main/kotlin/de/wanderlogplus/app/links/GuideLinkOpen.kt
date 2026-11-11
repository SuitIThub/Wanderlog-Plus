package de.wanderlogplus.app.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind

/** Where a guide link may lead, tried in order. */
sealed interface LinkTarget {
    data class Launch(val pkg: String) : LinkTarget
    data class View(val uri: String) : LinkTarget
}

private fun isWeb(u: String) = u.startsWith("https://") || u.startsWith("http://")

/** An installed app opens; otherwise its Play Store (app, then web), then its website. A page opens in the browser. */
fun guideLinkTargets(link: GuideLink, installed: Boolean): List<LinkTarget> = buildList {
    val pkg = link.androidPackage.trim()
    if (link.kind == GuideLinkKind.APP && pkg.isNotEmpty()) {
        if (installed) add(LinkTarget.Launch(pkg))
        add(LinkTarget.View("market://details?id=$pkg"))
        add(LinkTarget.View("https://play.google.com/store/apps/details?id=$pkg"))
    }
    link.url.trim().takeIf(::isWeb)?.let { add(LinkTarget.View(it)) }
}

/** Opens [link] the first way that works; false when nothing could open it. */
fun openGuideLink(context: Context, link: GuideLink): Boolean {
    val pm = context.packageManager
    val installed = link.androidPackage.isNotBlank() && pm.getLaunchIntentForPackage(link.androidPackage.trim()) != null
    for (t in guideLinkTargets(link, installed)) {
        val intent = when (t) {
            is LinkTarget.Launch -> pm.getLaunchIntentForPackage(t.pkg) ?: continue
            is LinkTarget.View -> Intent(Intent.ACTION_VIEW, Uri.parse(t.uri))
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
