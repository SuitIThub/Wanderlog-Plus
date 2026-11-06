@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package de.wanderlogplus.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A general travel guide (etiquette, language, how things work), shown where its topic comes up: at places, stays,
 * rides, days and on the today screen. [topic] is a key of the fixed catalog (`GuideTopics`); null for a free guide
 * from the chat, which then only shows through [extraTriggers] and [pinnedTo].
 */
@Serializable
data class Guide(
    val id: String,
    val topic: String? = null,
    /** The guide this one is a sub-topic of (Omakase under Restaurant). */
    val parentId: String? = null,
    val title: String = "",
    /** The most important in about three lines (preview on the today screen). */
    val summary: String = "",
    val sections: List<GuideSection> = emptyList(),
    val phrases: List<GuidePhrase> = emptyList(),
    val sources: List<String> = emptyList(),
    /** Rules on top of the catalog topic's own ones. */
    val extraTriggers: List<GuideTrigger> = emptyList(),
    /** Shown there even without a matching rule. */
    val pinnedTo: List<GuideTarget> = emptyList(),
    /** Never shown there. */
    val hiddenFrom: List<GuideTarget> = emptyList(),
    /** Epoch millis when the server last researched and wrote the text; 0 for unknown (older guides). */
    val writtenAt: Long = 0,
)

@Serializable
data class GuideSection(val heading: String, val text: String = "", val dos: List<String> = emptyList(), val donts: List<String> = emptyList())

/** A phrase to say or show: German, Japanese, romaji and when it helps. */
@Serializable
data class GuidePhrase(val german: String, val japanese: String, val romaji: String, val situation: String = "")

enum class GuideTargetType { PLACE, STAY, TRAIN, FLIGHT, DAY }

/** Where a guide is pinned or hidden; [id] is a saved place, stay, train or flight id, or a date "YYYY-MM-DD". */
@Serializable
data class GuideTarget(val type: GuideTargetType, val id: String)

/** What a trigger of the [GuideTrigger.Text] kind looks at. */
enum class TriggerOn { PLACE, STAY, ANY }

/** When a guide's topic comes up. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface GuideTrigger {
    /** One of [words] in the name or categories of a place or stay (ignoring case). */
    @Serializable
    @SerialName("text")
    data class Text(val words: List<String>, val on: TriggerOn = TriggerOn.ANY) : GuideTrigger

    @Serializable
    @SerialName("category")
    data class Category(val category: PlaceCategory) : GuideTrigger

    @Serializable
    @SerialName("stay")
    data object AnyStay : GuideTrigger

    /** A train; with [words], only one whose carrier, number or stops contain one of them. */
    @Serializable
    @SerialName("train")
    data class Train(val words: List<String> = emptyList()) : GuideTrigger

    @Serializable
    @SerialName("flight")
    data object AnyFlight : GuideTrigger

    /** Every trip day. */
    @Serializable
    @SerialName("always")
    data object Always : GuideTrigger
}

/** Per-trip guide settings (one record, id "prefs"): topics whose suggestion was turned down. */
@Serializable
data class GuidePrefs(val id: String = "prefs", val dismissedTopics: List<String> = emptyList())
