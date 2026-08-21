package com.aichathub.app.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Minimal, dependency-free Markdown → [AnnotatedString] renderer used by the
 * chat bubbles. It intentionally covers the subset models produce most often:
 * code blocks, inline code, bold, italic, strikethrough, headers, bullet/number
 * lists and links. Anything it does not recognize is passed through verbatim, so
 * it degrades gracefully instead of corrupting the text.
 *
 * Pure logic (no Android runtime state), unit-testable.
 */
object MarkdownFormatter {

    /**
     * Renders [text] as an [AnnotatedString] with simple inline styling.
     * [baseColor]/[accentColor] let the caller theme it without hardcoding.
     */
    fun render(
        text: String,
        baseColor: Color,
        accentColor: Color
    ): AnnotatedString {
        val builder = buildAnnotatedString {
            val lines = text.split("\n")
            var inFence = false
            val fenced = StringBuilder()
            var fenceIndent = ""
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trimStart()
                // Fenced code blocks ```lang ... ``` take precedence over inline
                // styling so code is never mangled by bold/italic rules.
                if (trimmed.startsWith("```")) {
                    if (!inFence) {
                        inFence = true
                        fenceIndent = line.take(line.length - trimmed.length)
                        fenced.setLength(0)
                    } else {
                        inFence = false
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accentColor)) {
                            append(fenceIndent)
                            append(fenced.toString().trim('\n'))
                        }
                        append("\n")
                    }
                    i++
                    continue
                }
                if (inFence) {
                    fenced.append(line).append('\n')
                    i++
                    continue
                }

                val header = parseHeader(trimmed)
                if (header != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(renderInline(header.content, baseColor, accentColor))
                    }
                    append("\n")
                    i++
                    continue
                }

                val listItem = parseList(trimmed)
                if (listItem != null) {
                    append(listItem.marker)
                    append(renderInline(listItem.content, baseColor, accentColor))
                    append("\n")
                    i++
                    continue
                }

                append(renderInline(line, baseColor, accentColor))
                append("\n")
                i++
            }
        }
        return builder
    }

    private class Header(val content: String)

    private fun parseHeader(trimmed: String): Header? {
        if (!trimmed.startsWith("#")) return null
        var level = 0
        while (level < trimmed.length && trimmed[level] == '#') level++
        if (level > 6) return null
        val content = trimmed.drop(level).trim().removePrefix(" ").trim()
        if (content.isEmpty()) return null
        return Header(content)
    }

    private class ListItem(val marker: String, val content: String)

    private fun parseList(trimmed: String): ListItem? {
        // Bullet list: "- ", "* ", "+ "
        val bullet = Regex("^([-*+]) +(.+)$").find(trimmed)
        if (bullet != null) {
            return ListItem("• ", bullet.groupValues[2])
        }
        // Numbered list: "1. ", "1) " etc.
        val numbered = Regex("^(\\d+)[.)] +(.+)$").find(trimmed)
        if (numbered != null) {
            return ListItem("${numbered.groupValues[1]}. ", numbered.groupValues[2])
        }
        return null
    }

    private fun androidx.compose.ui.text.AnnotatedString.Builder.renderInline(
        line: String,
        baseColor: Color,
        accentColor: Color
    ) {
        var cursor = 0
        val regex = Regex("""(\*\*[^*]+\*\*|__[^_]+__|\*[^*\n]+\*|_[^_\n]+_|~~[^~]+~~|`[^`]+`|\[[^\]]+\]\([^)]+\))""")
        for (match in regex.findAll(line)) {
            if (match.range.first > cursor) {
                append(line.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.drop(2).dropLast(2))
                    }
                }
                token.startsWith("__") && token.endsWith("__") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.drop(2).dropLast(2))
                    }
                }
                token.startsWith("*") && token.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(token.drop(1).dropLast(1))
                    }
                }
                token.startsWith("_") && token.endsWith("_") -> {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(token.drop(1).dropLast(1))
                    }
                }
                token.startsWith("~~") && token.endsWith("~~") -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(token.drop(2).dropLast(2))
                    }
                }
                token.startsWith("`") && token.endsWith("`") -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accentColor)) {
                        append(token.drop(1).dropLast(1))
                    }
                }
                token.startsWith("[") && token.contains("](") && token.endsWith(")") -> {
                    val label = token.substringAfter("[").substringBefore("]")
                    val url = token.substringAfter("](").substringBefore(")")
                    withStyle(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < line.length) {
            append(line.substring(cursor))
        }
    }
}