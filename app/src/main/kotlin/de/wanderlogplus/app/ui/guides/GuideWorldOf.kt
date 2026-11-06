package de.wanderlogplus.app.ui.guides

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.guide.GuideWorld

/** Everything guide matching needs, from the app's snapshot. */
fun TripSnapshot.guideWorld(): GuideWorld = GuideWorld(places, savedPlaces, stays, trains, flights, guides, days, dayItems)

/** The trip's dates (for suggestions over the whole trip). */
fun TripSnapshot.tripDates(): List<String> = queries()?.days()?.map { it.date }.orEmpty()
