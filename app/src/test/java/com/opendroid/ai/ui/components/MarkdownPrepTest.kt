package com.opendroid.ai.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streaming fence guard behind [MarkdownMessage]: balanced markdown must
 * pass through untouched, and a reply still streaming its code block must get
 * a parser-only closing fence appended.
 */
class MarkdownPrepTest {

    @Test
    fun `plain text is unchanged`() {
        val text = "Hello, no code here at all."
        assertEquals(text, closeOpenCodeFence(text))
    }

    @Test
    fun `closed fence is unchanged`() {
        val text = "Before\n```kotlin\nval x = 1\n```\nAfter"
        assertEquals(text, closeOpenCodeFence(text))
    }

    @Test
    fun `open fence gets closed`() {
        val text = "Before\n```kotlin\nval x = 1"
        assertEquals("$text\n```", closeOpenCodeFence(text))
    }

    @Test
    fun `open fence after trailing newline gets closed without extra blank line`() {
        val text = "```\ncode\n"
        assertEquals(text + "```", closeOpenCodeFence(text))
    }

    @Test
    fun `tilde fence is closed with tildes`() {
        val text = "~~~\ncode"
        assertEquals("$text\n~~~", closeOpenCodeFence(text))
    }

    @Test
    fun `two closed blocks are unchanged`() {
        val text = "```\na\n```\nmiddle\n```\nb\n```"
        assertEquals(text, closeOpenCodeFence(text))
    }

    @Test
    fun `inline backticks mid-line are not fences`() {
        val text = "Use ``` to open a fence, they said."
        assertEquals(text, closeOpenCodeFence(text))
    }

    @Test
    fun `indented fence line still counts`() {
        val text = "  ```python\nprint(1)"
        assertEquals("$text\n```", closeOpenCodeFence(text))
    }
}
