package com.aichathub.app.util

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormatterTest {

    private val base = Color.Gray
    private val accent = Color.Blue

    @Test
    fun `plain text passes through unchanged`() {
        val out = MarkdownFormatter.render("Hello world", base, accent)
        assertEquals("Hello world", out.text)
    }

    @Test
    fun `bold is styled and delimiters removed`() {
        val out = MarkdownFormatter.render("**bold**", base, accent)
        assertEquals("bold", out.text)
    }

    @Test
    fun `inline code keeps its content and drops backticks`() {
        val out = MarkdownFormatter.render("use `kotlin`", base, accent)
        assertEquals("use kotlin", out.text)
    }

    @Test
    fun `code block content is preserved`() {
        val out = MarkdownFormatter.render("```\nval x = 1\n```", base, accent)
        assertTrue(out.text.contains("val x = 1"))
    }

    @Test
    fun `headers drop hash prefixes`() {
        val out = MarkdownFormatter.render("# Title", base, accent)
        assertEquals("Title", out.text.trim())
    }

    @Test
    fun `bullet list markers are normalized`() {
        val out = MarkdownFormatter.render("- item", base, accent)
        assertTrue(out.text.contains("•"))
    }

    @Test
    fun `link label is kept without the url`() {
        val out = MarkdownFormatter.render("[click here](https://x.dev)", base, accent)
        assertEquals("click here", out.text)
    }

    @Test
    fun `strikethrough removes tildes`() {
        val out = MarkdownFormatter.render("~~gone~~", base, accent)
        assertEquals("gone", out.text)
    }

    @Test
    fun `mixed markdown keeps overall text`() {
        val out = MarkdownFormatter.render(
            "# Hi\n\nSome **bold** and `code` here.",
            base,
            accent
        )
        assertTrue(out.text.contains("Hi"))
        assertTrue(out.text.contains("bold"))
        assertTrue(out.text.contains("code"))
    }
}