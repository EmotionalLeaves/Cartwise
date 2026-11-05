package com.emotionalleaves.cartwise.taste

/** The 30-second start quiz: pick 5–10 so the feed works on day one. */
object Quiz {
    data class Chip(val slug: String, val label: String, val query: String, val root: String)

    val CHIPS = listOf(
        Chip("kitchen", "Kitchen gear", "kitchen gadgets cookware", "Home & Kitchen"),
        Chip("coffee", "Coffee & tea", "coffee brewing gear", "Home & Kitchen"),
        Chip("keyboards", "Mechanical keyboards", "mechanical keyboard", "Electronics"),
        Chip("pcgaming", "PC & gaming gear", "pc gaming accessories", "Electronics"),
        Chip("phone", "Phone accessories", "phone accessories charger", "Cell Phones & Accessories"),
        Chip("audio", "Headphones & audio", "headphones earbuds", "Electronics"),
        Chip("smarthome", "Smart home", "smart home devices", "Electronics"),
        Chip("office", "Home office", "home office desk accessories", "Office Products"),
        Chip("fitness", "Fitness", "home gym fitness equipment", "Sports & Outdoors"),
        Chip("running", "Running", "running gear", "Sports & Outdoors"),
        Chip("camping", "Camping & hiking", "camping hiking gear", "Sports & Outdoors"),
        Chip("cycling", "Cycling", "bike accessories", "Sports & Outdoors"),
        Chip("books", "Books", "books", "Books"),
        Chip("boardgames", "Board games", "board games", "Toys & Games"),
        Chip("videogames", "Video games", "video games", "Video Games"),
        Chip("tools", "Tools & DIY", "power tools hand tools", "Tools & Home Improvement"),
        Chip("garden", "Gardening", "gardening tools", "Patio, Lawn & Garden"),
        Chip("skincare", "Skincare", "skincare", "Beauty & Personal Care"),
        Chip("grooming", "Grooming", "grooming shaving", "Beauty & Personal Care"),
        Chip("dogs", "Dog supplies", "dog supplies", "Pet Supplies"),
        Chip("cats", "Cat supplies", "cat supplies", "Pet Supplies"),
        Chip("baby", "Baby & kids", "baby essentials", "Baby"),
        Chip("cleaning", "Cleaning", "cleaning supplies", "Health & Household"),
        Chip("snacks", "Snacks & pantry", "snacks", "Grocery & Gourmet Food"),
        Chip("photo", "Photography", "camera accessories", "Electronics"),
        Chip("art", "Art supplies", "art supplies", "Arts, Crafts & Sewing"),
        Chip("music", "Music gear", "guitar accessories", "Musical Instruments"),
        Chip("3dprint", "3D printing", "3d printer filament", "Industrial & Scientific"),
        Chip("travel", "Travel gear", "travel accessories", "Clothing, Shoes & Jewelry"),
        Chip("clothes", "Everyday clothing", "everyday basics", "Clothing, Shoes & Jewelry"),
        Chip("car", "Car accessories", "car accessories", "Automotive"),
        Chip("storage", "Storage & organizing", "home storage organization", "Home & Kitchen"),
    )
}
