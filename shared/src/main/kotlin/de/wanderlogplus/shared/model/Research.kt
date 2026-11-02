package de.wanderlogplus.shared.model

import kotlinx.serialization.Serializable

/** One aspect a place can be judged by; [question] tells the model what to look at. */
data class Criterion(val key: String, val label: String, val question: String)

/** The catalogue of assessment criteria the user picks from (Einstellungen → KI-Einschätzung). */
object Criteria {
    val ALL: List<Criterion> = listOf(
        Criterion("highlight", "Highlight-Faktor", "Wie sehr ist das ein Höhepunkt der Region, den man gesehen haben sollte?"),
        Criterion("unique", "Einzigartigkeit", "Gibt es so etwas anderswo kaum?"),
        Criterion("reviews", "Bewertungen", "Wie gut sind die Bewertungen von Besuchern?"),
        Criterion("crowds", "Andrang", "Wie wenig voll ist es? 5 = angenehm leer, 1 = überlaufen."),
        Criterion("authentic", "Authentisch statt touristisch", "5 = authentisch und lokal, 1 = sehr touristisch."),
        Criterion("booking", "Buchbarkeit", "Wie leicht kommt man hinein? 5 = einfach hingehen, 1 = Reservierung Wochen vorher nötig."),
        Criterion("waiting", "Wartezeiten", "5 = kaum Wartezeit, 1 = lange Schlangen."),
        Criterion("price", "Preis-Leistung", "Wie gut ist das Verhältnis von Kosten zu Erlebnis?"),
        Criterion("time", "Zeitaufwand", "5 = schnell zu besuchen, 1 = braucht einen halben Tag oder mehr."),
        Criterion("access", "Erreichbarkeit", "Wie gut ist der Ort mit Bahn/Bus oder zu Fuß erreichbar?"),
        Criterion("season", "Passt zur Reisezeit (Mai)", "Wie gut ist der Besuch im Mai (Wetter, Saison, Golden Week)?"),
        Criterion("weather", "Wetterunabhängig", "5 = auch bei Regen gut, 1 = nur bei gutem Wetter sinnvoll."),
        Criterion("photo", "Fotospot", "Wie lohnend ist der Ort für Fotos?"),
        Criterion("atmosphere", "Atmosphäre & Ruhe", "Wie schön und entspannt ist die Stimmung vor Ort?"),
        Criterion("culture", "Kultur & Geschichte", "Wie viel japanische Kultur oder Geschichte erlebt man?"),
        Criterion("nature", "Natur", "Wie viel Natur und Landschaft bietet der Ort?"),
        Criterion("food", "Essen & Trinken", "Wie gut ist das kulinarische Erlebnis (bei Lokalen) oder das Angebot vor Ort?"),
        Criterion("english", "Ohne Japanisch machbar", "Wie gut kommt man ohne Japanischkenntnisse zurecht (Schilder, Menüs, Personal)?"),
    )

    val DEFAULT: List<String> = listOf("highlight", "unique", "crowds", "authentic", "booking", "price", "time", "season")

    fun byKey(key: String): Criterion? = ALL.firstOrNull { it.key == key }
}

/** One criterion's score (1–5) with a short German note. */
@Serializable
data class CriterionScore(val key: String, val label: String = "", val score: Int = 0, val note: String = "")

/**
 * What a research run found out about a place: the multi-criteria judgement with its overall verdict, and the
 * practical material (insider tips, hidden spots nearby, good-to-know facts, tips, warnings, sources).
 * Stored on the SavedPlace; [researchedAt] in epoch millis (0 = judgement only, no web research).
 */
@Serializable
data class PlaceDossier(
    val criteria: List<CriterionScore> = emptyList(),
    val verdict: String = "",
    val insiderTips: List<String> = emptyList(),
    val hiddenSpots: List<String> = emptyList(),
    val goodToKnow: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val researchedAt: Long = 0,
)
