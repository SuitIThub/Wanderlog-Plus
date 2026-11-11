package de.wanderlogplus.server.balances

import de.wanderlogplus.server.PlaceDetailsTransport
import de.wanderlogplus.server.PlacesApi
import de.wanderlogplus.server.PlacesPhotoTransport
import de.wanderlogplus.server.RoutesTransport
import de.wanderlogplus.server.ai.PlacesSearchTransport
import de.wanderlogplus.server.tabelog.GoogleJaTransport

// Wrappers that count every Google call in [GoogleUsage] before passing it on (wired in Main).

fun counted(u: GoogleUsage, t: PlacesSearchTransport) = PlacesSearchTransport { body -> u.count(TEXT); t.searchText(body) }

fun counted(u: GoogleUsage, t: PlaceDetailsTransport) = PlaceDetailsTransport { id -> u.count(DETAILS); t.details(id) }

fun counted(u: GoogleUsage, t: PlacesApi) = PlacesApi { path, body, mask ->
    u.count(if ("searchNearby" in path) NEARBY else if ("searchText" in path) TEXT else DETAILS)
    t.post(path, body, mask)
}

fun counted(u: GoogleUsage, t: PlacesPhotoTransport) = object : PlacesPhotoTransport {
    override fun details(googlePlaceId: String): Pair<Int, String> { u.count(DETAILS); return t.details(googlePlaceId) }
    override fun media(name: String, maxWidth: Int): Pair<Int, ByteArray> { u.count(PHOTOS); return t.media(name, maxWidth) }
}

fun counted(u: GoogleUsage, t: RoutesTransport) = RoutesTransport { body -> u.count(ROUTES); t.post(body) }

fun counted(u: GoogleUsage, t: GoogleJaTransport) = object : GoogleJaTransport {
    override fun details(id: String): Pair<Int, String> { u.count(DETAILS); return t.details(id) }
    override fun search(body: String): Pair<Int, String> { u.count(TEXT); return t.search(body) }
}

private const val DETAILS = "Details"
private const val TEXT = "Textsuche"
private const val NEARBY = "Umgebungssuche"
private const val PHOTOS = "Fotos"
private const val ROUTES = "Routen"
