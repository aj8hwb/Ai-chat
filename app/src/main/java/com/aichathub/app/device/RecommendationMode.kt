package com.aichathub.app.device

/**
 * The user's optimization goal when browsing model recommendations.
 */
enum class RecommendationMode(val label: String) {
    BEST_BALANCE("Best Balance"),
    FASTEST("Fastest"),
    BEST_QUALITY("Best Quality"),
    SMALLEST("Smallest")
}
