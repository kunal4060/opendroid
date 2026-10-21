package com.opendroid.ai.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedContentTest {

    private fun image(name: String) = SharedImageRef(
        file = File("/tmp/$name"),
        displayName = name,
        mimeType = "image/jpeg"
    )

    @Test
    fun `browser share keeps both page title and url`() {
        val content = SharedContent.fromRaw(
            text = "https://example.com/article",
            subject = "An Interesting Article",
            images = emptyList()
        )
        assertEquals(SharedContent.Kind.LINK, content.kind)
        assertEquals(
            "Shared link: \"An Interesting Article\" — https://example.com/article",
            content.composerPrefill()
        )
        assertEquals("An Interesting Article", content.suggestedSessionTitle())
    }

    @Test
    fun `bare url without title still classifies as link`() {
        val content = SharedContent.fromRaw("https://example.com/x", null, emptyList())
        assertEquals(SharedContent.Kind.LINK, content.kind)
        assertEquals("Shared link: https://example.com/x", content.composerPrefill())
    }

    @Test
    fun `url inside a sentence is plain text, not a link share`() {
        val text = "look at https://example.com/x please"
        val content = SharedContent.fromRaw(text, null, emptyList())
        assertEquals(SharedContent.Kind.TEXT, content.kind)
        assertEquals(text, content.composerPrefill())
    }

    @Test
    fun `plain text share arrives verbatim`() {
        val content = SharedContent.fromRaw("  buy milk tomorrow  ", null, emptyList())
        assertEquals(SharedContent.Kind.TEXT, content.kind)
        assertEquals("buy milk tomorrow", content.composerPrefill())
    }

    @Test
    fun `images only share names its files`() {
        val content = SharedContent.fromRaw(null, null, listOf(image("cat.jpg")))
        assertEquals(SharedContent.Kind.IMAGES, content.kind)
        assertEquals("Shared image: cat.jpg", content.composerPrefill())
        assertEquals("Shared image: cat.jpg", content.suggestedSessionTitle())
    }

    @Test
    fun `text plus images is mixed and keeps both`() {
        val content = SharedContent.fromRaw(
            text = "what is this?",
            subject = null,
            images = listOf(image("a.png"), image("b.png"))
        )
        assertEquals(SharedContent.Kind.MIXED, content.kind)
        assertEquals(
            "what is this?\nShared image: a.png\nShared image: b.png",
            content.composerPrefill()
        )
    }

    @Test
    fun `empty and blank shares classify as empty`() {
        assertTrue(SharedContent.fromRaw(null, null, emptyList()).isEmpty)
        assertTrue(SharedContent.fromRaw("   ", "  ", emptyList()).isEmpty)
        assertEquals("", SharedContent.fromRaw(null, null, emptyList()).composerPrefill())
    }

    @Test
    fun `additional images note names everything past the first image`() {
        assertNull(SharedContent.additionalImagesNote(emptyList()))
        assertNull(SharedContent.additionalImagesNote(listOf(image("one.jpg"))))
        assertEquals(
            "[+1 more shared image: two.jpg]",
            SharedContent.additionalImagesNote(listOf(image("one.jpg"), image("two.jpg")))
        )
        assertEquals(
            "[+2 more shared images: two.jpg, three.jpg]",
            SharedContent.additionalImagesNote(
                listOf(image("one.jpg"), image("two.jpg"), image("three.jpg"))
            )
        )
    }
}
