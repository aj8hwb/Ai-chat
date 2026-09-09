package com.aichathub.app.ui.screens.chat

import androidx.compose.ui.text.AnnotatedString

/**
 * Highlights occurrences of [query] in [text] by returning an [AnnotatedString]
 * with bold styling on matching regions.
 */
fun highlightSearchText(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val builder = AnnotatedString.Builder()
    var remaining = text
    val lowerQuery = query.lowercase()
    while (remaining.isNotEmpty()) {
        val matchIndex = remaining.lowercase().indexOf(lowerQuery)
        if (matchIndex == -1) {
            builder.append(remaining)
            break
        }
        builder.append(remaining.substring(0, matchIndex))
        builder.append(remaining.substring(matchIndex, matchIndex + query.length))
        remaining = remaining.substring(matchIndex + query.length)
    }
    return builder.toAnnotatedString()
}
