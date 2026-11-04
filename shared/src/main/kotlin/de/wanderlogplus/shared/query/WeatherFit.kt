package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace

/** Which places are better in good weather (outside, views, hikes): what a rainy day puts at risk. */
object WeatherFit {
    /** Google place types that are mostly outdoors. */
    private val OUTDOOR_TYPES = setOf(
        "park", "national_park", "state_park", "hiking_area", "garden", "botanical_garden", "beach", "campground",
        "natural_feature", "zoo", "amusement_park", "water_park", "observation_deck", "dog_park", "marina", "ski_resort",
    )

    /** Words in names that mean outdoors (German, English, Japanese). */
    private val OUTDOOR_WORDS = listOf(
        "wanderung", "wanderweg", "trail", "hike", "hiking", "nakasendo", "garten", "garden", "park", "strand", "beach",
        "aussicht", "viewpoint", "gipfel", "berg", "mount", "wasserfall", "falls", "schlucht", "gorge", "bambus", "bamboo",
        "庭園", "公園", "山", "滝", "峠", "道",
    )

    /**
     * True when the place is better in good weather: the research judged it weather-dependent (criterion "weather"
     * at most 2), else by its place types or name.
     */
    fun isWeatherSensitive(saved: SavedPlace?, place: Place?): Boolean {
        saved?.dossier?.criteria?.firstOrNull { it.key == "weather" && it.score in 1..5 }?.let { return it.score <= 2 }
        val p = place ?: return false
        if (p.categories.any { it.lowercase() in OUTDOOR_TYPES }) return true
        val name = p.name.lowercase()
        return OUTDOOR_WORDS.any { w -> if (w.length <= 2) name.endsWith(w) else name.contains(w) }
    }
}
