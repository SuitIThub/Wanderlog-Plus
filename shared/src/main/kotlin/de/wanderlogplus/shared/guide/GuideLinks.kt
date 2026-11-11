package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind

/** The links of several guides together, e.g. for a place that shows three guides. */
object GuideLinks {
    /** Same app (package id) or same page (url, ignoring case and a trailing slash). */
    fun key(l: GuideLink): String =
        if (l.kind == GuideLinkKind.APP && l.androidPackage.isNotBlank()) "app:" + l.androidPackage
        else (if (l.kind == GuideLinkKind.APP) "appurl:" else "web:") + l.url.trim().lowercase().trimEnd('/')

    /** Every link of [guides] once, in guide order; the first occurrence wins. */
    fun union(guides: List<Guide>): List<GuideLink> = guides.flatMap { it.links }.distinctBy(::key)
}
