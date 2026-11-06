package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.GuideTrigger
import de.wanderlogplus.shared.model.GuideTrigger.Always
import de.wanderlogplus.shared.model.GuideTrigger.AnyFlight
import de.wanderlogplus.shared.model.GuideTrigger.AnyStay
import de.wanderlogplus.shared.model.GuideTrigger.Category
import de.wanderlogplus.shared.model.GuideTrigger.Text
import de.wanderlogplus.shared.model.GuideTrigger.Train
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.TriggerOn.PLACE
import de.wanderlogplus.shared.model.TriggerOn.STAY

/** A topic of the fixed guide catalog and when it comes up. */
data class GuideTopic(val key: String, val name: String, val parentKey: String? = null, val triggers: List<GuideTrigger>)

/** The fixed catalog; its order is the order topics are shown and suggested in. */
object GuideTopics {
    val all: List<GuideTopic> = listOf(
        GuideTopic("sprache-basics", "Japanisch für unterwegs", triggers = listOf(Always)),
        GuideTopic("bezahlen", "Bezahlen, Bargeld & Trinkgeld", triggers = listOf(Always)),
        GuideTopic("onsen", "Onsen & Sentō", triggers = listOf(Text(listOf("onsen", "hot spring", "sento", "sentō", "spa", "温泉")), Text(listOf("ryokan"), STAY))),
        GuideTopic("ryokan", "Im Ryokan", triggers = listOf(Text(listOf("ryokan", "minshuku", "旅館"), STAY))),
        GuideTopic("hotel", "Im Hotel", triggers = listOf(AnyStay)),
        GuideTopic("restaurant", "Im Restaurant bestellen", triggers = listOf(Category(PlaceCategory.FOOD))),
        GuideTopic("omakase", "Omakase & Sushi-Theke", "restaurant", listOf(Text(listOf("omakase", "sushi", "kaiseki"), PLACE))),
        GuideTopic("izakaya", "Izakaya", "restaurant", listOf(Text(listOf("izakaya", "yakitori"), PLACE))),
        GuideTopic("ramen", "Ramen & Ticketautomat", "restaurant", listOf(Text(listOf("ramen", "udon", "soba"), PLACE))),
        GuideTopic("yakiniku", "Yakiniku & Grill", "restaurant", listOf(Text(listOf("yakiniku", "barbecue", "offal"), PLACE))),
        GuideTopic(
            "tempel-schrein", "Tempel & Schreine",
            triggers = listOf(
                Text(listOf("buddhist temple", "shinto shrine", "place of worship", "sacred & religious", "temple", "shrine", "jinja", "dera", "寺", "神社"), PLACE),
            ),
        ),
        GuideTopic("bahn", "Bahnfahren in Japan", triggers = listOf(Train())),
        GuideTopic(
            "shinkansen", "Shinkansen", "bahn",
            listOf(Train(listOf("shinkansen", "nozomi", "hikari", "kodama", "hayabusa", "mizuho", "sakura", "tsubame", "kagayaki", "hakutaka", "新幹線"))),
        ),
        GuideTopic("flughafen", "Flughafen & Einreise", triggers = listOf(AnyFlight)),
        GuideTopic("wandern", "Wandern & alte Handelswege", triggers = listOf(Text(listOf("hiking area", "trail", "historic walking areas", "nakasendo", "kumano"), PLACE))),
        GuideTopic("museum", "Museen", triggers = listOf(Text(listOf("museum"), PLACE))),
        GuideTopic("garten-park", "Gärten & Parks", triggers = listOf(Text(listOf("garden", "park", "nature & parks"), PLACE))),
        GuideTopic("einkaufen", "Einkaufen & Tax-free", triggers = listOf(Category(PlaceCategory.SHOPPING))),
        GuideTopic("nachtleben", "Abends unterwegs", triggers = listOf(Category(PlaceCategory.NIGHTLIFE))),
        GuideTopic("burg", "Burgen", triggers = listOf(Text(listOf("castle", "城"), PLACE))),
    )

    private val byKey = all.associateBy { it.key }
    private val order = all.withIndex().associate { (i, t) -> t.key to i }

    fun byKey(key: String?): GuideTopic? = key?.let { byKey[it] }

    /** Position in the catalog; topics outside it (free guides) come last. */
    fun orderOf(key: String?): Int = key?.let { order[it] } ?: Int.MAX_VALUE
}
