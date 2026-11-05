package com.emotionalleaves.cartwise.feed

import java.util.Locale

object Categories {
    private val SLUG = mapOf(
        "electronics" to "electronics", "computers" to "pc", "computers & accessories" to "pc",
        "home & kitchen" to "home-garden", "kitchen & dining" to "kitchen", "tools & home improvement" to "hi",
        "sports & outdoors" to "sporting-goods", "toys & games" to "toys-and-games", "books" to "books",
        "beauty & personal care" to "beauty", "health & household" to "hpc", "pet supplies" to "pet-supplies",
        "grocery & gourmet food" to "grocery", "office products" to "office-products",
        "cell phones & accessories" to "wireless", "video games" to "videogames", "baby" to "baby-products",
        "baby products" to "baby-products", "automotive" to "automotive", "patio, lawn & garden" to "lawn-garden",
        "garden & outdoor" to "lawn-garden", "arts, crafts & sewing" to "arts-crafts",
        "clothing, shoes & jewelry" to "fashion", "musical instruments" to "musical-instruments",
        "industrial & scientific" to "industrial", "camera & photo" to "photo", "appliances" to "appliances",
    )

    fun bestsellerSlug(root: String): String? = SLUG[root.lowercase(Locale.US).trim()]

    /** "One category over" from an interest: neighbouring searches per top-level category. */
    private val ADJ = mapOf(
        "electronics" to listOf("smart home gadgets", "camera accessories", "desk setup accessories", "portable power"),
        "computers" to listOf("desk setup accessories", "smart home gadgets", "cable management"),
        "home & kitchen" to listOf("home organization", "table linens", "coffee gear", "baking tools"),
        "kitchen & dining" to listOf("baking tools", "coffee gear", "pantry storage"),
        "sports & outdoors" to listOf("recovery tools", "outdoor cooking", "travel gear", "hydration"),
        "tools & home improvement" to listOf("garage organization", "garden tools", "workbench accessories"),
        "toys & games" to listOf("puzzles", "party games", "strategy card games"),
        "books" to listOf("journals notebooks", "reading lights", "bookends"),
        "beauty & personal care" to listOf("bath accessories", "hair tools", "nail care"),
        "health & household" to listOf("sleep comfort", "cleaning gadgets", "first aid"),
        "pet supplies" to listOf("pet tech gadgets", "pet grooming", "pet travel"),
        "grocery & gourmet food" to listOf("specialty snacks", "spices seasonings", "hot sauce"),
        "office products" to listOf("desk organizers", "fountain pens", "planners"),
        "cell phones & accessories" to listOf("phone photography accessories", "wireless chargers", "car phone mounts"),
        "video games" to listOf("gaming chairs", "retro gaming", "controller accessories"),
        "baby" to listOf("kids books", "toddler toys", "family travel"),
        "automotive" to listOf("car cleaning", "road trip gear", "garage tools"),
        "patio, lawn & garden" to listOf("outdoor lighting", "bird feeders", "planters"),
        "arts, crafts & sewing" to listOf("drawing tablets", "craft storage", "calligraphy"),
        "clothing, shoes & jewelry" to listOf("travel accessories", "wallets", "watches"),
        "musical instruments" to listOf("home recording", "music stands", "studio headphones"),
        "industrial & scientific" to listOf("electronics kits", "measuring tools", "soldering"),
        "camera & photo" to listOf("lighting kits", "camera bags", "tripods"),
    )

    fun adjacentQuery(root: String?, fallback: String, salt: Int): String {
        val list = root?.let { ADJ[it.lowercase(Locale.US).trim()] }
        return if (list.isNullOrEmpty()) "$fallback accessories" else list[Math.floorMod(salt, list.size)]
    }
}
