package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextUtilsTest {

    @Test
    fun `takeNoSplit keeps short strings intact`() {
        assertEquals("hello", TextUtils.takeNoSplit("hello", 10))
    }

    @Test
    fun `takeNoSplit cuts longer strings at the bound`() {
        assertEquals("hell", TextUtils.takeNoSplit("hello", 4))
    }

    @Test
    fun `takeNoSplit does not split an emoji surrogate pair`() {
        // "😀" is two UTF-16 code units; cutting at the high surrogate must
        // back off so the pair is not half-kept.
        val s = "abc😀xyz"
        val cut = TextUtils.takeNoSplit(s, 4)
        assertEquals("abc", cut)
    }

    @Test
    fun `takeNoSplit keeps emoji intact when the cut lands after the pair`() {
        val s = "abc😀"
        assertEquals("abc😀", TextUtils.takeNoSplit(s, 5))
    }

    @Test
    fun `takeNoSplit handles empty and zero`() {
        assertEquals("", TextUtils.takeNoSplit("", 0))
        assertEquals("", TextUtils.takeNoSplit("abc", 0))
    }

    @Test
    fun `takeNoSplit with max beyond length returns the whole string`() {
        val s = "a"
        assertEquals(s, TextUtils.takeNoSplit(s, 10))
    }
}