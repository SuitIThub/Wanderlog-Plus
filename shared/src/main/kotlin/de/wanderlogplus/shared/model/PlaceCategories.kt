package de.wanderlogplus.shared.model

/** The trip category of a place from Google's place types (first type that says something wins). */
object PlaceCategories {
    private val food = setOf("restaurant", "cafe", "bakery", "meal_takeaway", "meal_delivery", "food", "coffee_shop", "tea_house", "dessert_shop", "confectionery")
    private val nightlife = setOf("bar", "night_club", "pub", "wine_bar", "karaoke")
    private val shopping = setOf("store", "shopping_mall", "market", "department_store", "supermarket", "book_store", "gift_shop")
    private val sight = setOf(
        "tourist_attraction", "museum", "park", "place_of_worship", "hindu_temple", "buddhist_temple", "shinto_shrine", "church",
        "art_gallery", "aquarium", "zoo", "amusement_park", "historical_landmark", "observation_deck", "national_park", "garden",
        "castle", "monument", "cultural_landmark", "historical_place",
    )

    fun fromGoogle(types: List<String>): PlaceCategory {
        for (t in types) {
            when {
                t in food || t.endsWith("_restaurant") -> return PlaceCategory.FOOD
                t in nightlife -> return PlaceCategory.NIGHTLIFE
                t in shopping || t.endsWith("_store") || t.endsWith("_shop") -> return PlaceCategory.SHOPPING
                t in sight -> return PlaceCategory.SIGHT
            }
        }
        return PlaceCategory.OTHER
    }
}
