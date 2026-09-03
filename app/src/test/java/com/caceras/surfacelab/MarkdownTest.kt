package com.caceras.surfacelab

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Markdown rendering, on Robolectric.
 *
 * Separate from LogicTest because render() returns a real
 * SpannableStringBuilder: on the stub android.jar every one of its methods
 * throws "not mocked", which is a failure about the test harness rather than
 * about the code. Robolectric supplies the real implementation.
 *
 *     gradle testCoreDebugUnitTest
 */
@RunWith(AndroidJUnit4::class)
class MarkdownTest {

    @Test
    fun `markdown punctuation does not reach the screen`() {
        // The answer arrived as: *   **Ukraine War:** Fighting remains intense
        // and that is exactly what the screen showed, asterisks and all.
        val rendered = Markdown.render("*   **Ukraine War:** Fighting is intense").toString()
        assertFalse("asterisks survived: $rendered", rendered.contains("*"))
        assertTrue(rendered.startsWith("•"))
        assertTrue(rendered.contains("Ukraine War:"))
        assertTrue(rendered.contains("Fighting is intense"))
    }

    @Test
    fun `headings and bold lose their marks but keep their words`() {
        val rendered = Markdown.render("## Today\n**Bold** and plain").toString()
        assertFalse(rendered.contains("#"))
        assertFalse(rendered.contains("*"))
        assertTrue(rendered.contains("Today"))
        assertTrue(rendered.contains("Bold and plain"))
    }

    @Test
    fun `ordinary text passes through untouched`() {
        val plain = "No markdown here, just a sentence."
        assertEquals(plain, Markdown.render(plain).toString())
    }

    @Test
    fun `bold really is bold, not just stripped of its stars`() {
        // Removing the asterisks and dropping the emphasis would pass every
        // assertion above while losing the entire point of rendering.
        val rendered = Markdown.render("**Ukraine War:** Fighting is intense")
        val spans = (rendered as android.text.Spanned).getSpans(
            0, rendered.length, android.text.style.StyleSpan::class.java
        )
        assertTrue("no bold span was applied", spans.isNotEmpty())
        assertEquals(
            android.graphics.Typeface.BOLD,
            spans.first().style
        )
        assertEquals("the span does not cover the bolded words",
            "Ukraine War:", rendered.subSequence(
                (rendered).getSpanStart(spans.first()),
                (rendered).getSpanEnd(spans.first())
            ).toString())
    }
}
