package com.aichathub.app.domain.model

/** A recommendation result for a single model. */
data class Recommendation(
    val model: CatalogModel,
    val level: CompatibilityLevel,
    val score: Int,
    val reason: String,
    val quantizationNote: String? = null
)
