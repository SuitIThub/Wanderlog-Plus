package de.wanderlogplus.app.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.englishUrl
import java.net.URLEncoder

enum class LinkKind { NAVIGATE, TRANSIT, WEBSITE, PHONE, TABELOG, CUSTOM }

data class ExternalLink(val kind: LinkKind, val label: String, val uri: String)

interface ExternalLinkProvider {
    /** [category] is null for places without a saved category (e.g. stays); no Tabelog link then. */
    fun linksFor(place: Place, category: PlaceCategory?): List<ExternalLink>
}

object GoogleExternalLinks : ExternalLinkProvider {
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    // Double.toString() is Locale-independent (34.9671, never 34,9671).
    private fun mapsUri(place: Place, transit: Boolean): String? {
        val destination = if (place.lat == 0.0 && place.lng == 0.0) {
            val text = listOfNotNull(place.name.takeIf { it.isNotBlank() }, place.address?.takeIf { it.isNotBlank() }).joinToString(" ")
            if (text.isEmpty()) return null
            enc(text)
        } else {
            "${place.lat},${place.lng}"
        }
        return buildString {
            append("https://www.google.com/maps/dir/?api=1&destination=").append(destination)
            if (place.googlePlaceId.isNotBlank()) append("&destination_place_id=").append(enc(place.googlePlaceId))
            if (transit) append("&travelmode=transit")
        }
    }

    override fun linksFor(place: Place, category: PlaceCategory?): List<ExternalLink> = buildList {
        mapsUri(place, transit = false)?.let { add(ExternalLink(LinkKind.NAVIGATE, "Navigation", it)) }
        mapsUri(place, transit = true)?.let { add(ExternalLink(LinkKind.TRANSIT, "ÖPNV", it)) }
        place.website?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { add(ExternalLink(LinkKind.WEBSITE, "Website", it)) }
        place.phone?.filter { it == '+' || it in '0'..'9' }?.takeIf { p -> p.count { it in '0'..'9' } >= 3 }
            ?.let { add(ExternalLink(LinkKind.PHONE, "Anrufen", "tel:$it")) }
        if (category == PlaceCategory.FOOD || category == PlaceCategory.NIGHTLIFE) {
            // The entry itself once the server found it, else Tabelog's search for the name.
            val entry = place.tabelog?.takeIf { it.status == de.wanderlogplus.shared.model.TabelogStatus.FOUND }?.englishUrl()?.takeIf { it.isNotBlank() }
            when {
                entry != null -> add(ExternalLink(LinkKind.TABELOG, "Tabelog", entry))
                place.name.isNotBlank() -> add(ExternalLink(LinkKind.TABELOG, "Tabelog", "https://tabelog.com/rstLst/?vs=1&sw=${enc(place.name)}"))
            }
        }
    }
}

/** ACTION_DIAL for tel:, ACTION_VIEW otherwise; false when no app handles it. */
/** A Tabelog restaurant page (not the search): worth handing to the Tabelog app. */
fun opensInTabelogApp(link: ExternalLink): Boolean =
    link.kind == LinkKind.TABELOG && de.wanderlogplus.shared.model.canonicalTabelogUrl(link.uri) != null

fun openExternalLink(context: Context, link: ExternalLink): Boolean {
    if (opensInTabelogApp(link)) return openTabelog(context, link.uri)
    val action = if (link.uri.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW
    val intent = Intent(action, Uri.parse(link.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
