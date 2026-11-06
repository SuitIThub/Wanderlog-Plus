package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.GuidePrefs
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.model.PlaceStatus

/** Catalog topics the trip touches that have no guide yet (worked out without the model). */
object GuideSuggestions {
    /** Topics of every trip day, saved place (not dropped), stay, train and flight; catalog order, each once. */
    fun topicsInTrip(w: GuideWorld, days: List<String>): List<String> {
        val targets = days.map { GuideTarget(GuideTargetType.DAY, it) } +
            w.saved.filter { it.status != PlaceStatus.DROPPED }.map { GuideTarget(GuideTargetType.PLACE, it.id) } +
            w.stays.map { GuideTarget(GuideTargetType.STAY, it.id) } +
            w.trains.map { GuideTarget(GuideTargetType.TRAIN, it.id) } +
            w.flights.map { GuideTarget(GuideTargetType.FLIGHT, it.id) }
        val found = targets.flatMap { GuideMatch.topicsFor(it, w) }.toSet()
        return GuideTopics.all.map { it.key }.filter { it in found }
    }

    /** Trip topics without a guide and not turned down. */
    fun open(w: GuideWorld, days: List<String>, prefs: GuidePrefs): List<String> = withoutCovered(topicsInTrip(w, days), w, prefs)

    /** Topics of [date] without a guide and not turned down. */
    fun openOn(date: String, w: GuideWorld, prefs: GuidePrefs): List<String> =
        withoutCovered(GuideMatch.topicsFor(GuideTarget(GuideTargetType.DAY, date), w), w, prefs)

    private fun withoutCovered(topics: List<String>, w: GuideWorld, prefs: GuidePrefs): List<String> {
        val covered = w.guides.mapNotNull { it.topic }.toSet() + prefs.dismissedTopics
        return topics.filter { it !in covered }
    }
}
