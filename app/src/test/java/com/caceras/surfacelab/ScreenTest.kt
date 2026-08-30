package com.caceras.surfacelab

import android.app.Activity
import android.content.Intent
import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Renders the real activities on the JVM.
 *
 * These exist because three layout bugs shipped that a compile and a static
 * checker both waved through: padding set in pixels rather than dp, no window
 * insets on an edge-to-edge target, and a row of suggestions that was written
 * and never added to a view. Every one of them is caught below.
 *
 * Run against the core flavour, whose brain is deterministic:
 *     gradle testCoreDebugUnitTest
 */
@RunWith(AndroidJUnit4::class)
class ScreenTest {

    private fun descendants(view: View): List<View> =
        if (view !is ViewGroup) listOf(view)
        else listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }

    private fun texts(root: View) =
        descendants(root).filterIsInstance<TextView>().map { it.text.toString() }

    // ---------------------------------------------------------------- main

    private fun launchMain(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    @Test
    fun `spacing is density-scaled, not raw pixels`() {
        val activity = launchMain().get()
        val scroll = activity.findViewById<View>(android.R.id.content)
            .let { descendants(it).filterIsInstance<ScrollView>().first() }
        val column = scroll.getChildAt(0)

        val density = activity.resources.displayMetrics.density
        val expected = (20 * density).toInt()

        assertEquals("left padding is not dp-scaled", expected, column.paddingLeft)
        assertEquals("right padding is not dp-scaled", expected, column.paddingRight)
    }

    private fun bars(top: Int, bottom: Int) = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, top, 0, bottom))
        .build()

    @Test
    fun `the scroll container consumes the system bars`() {
        // Targeting SDK 35+ means edge-to-edge whether the app asks or not, so
        // without this the first row sits under the status bar. Asserted by
        // behaviour: hand it real insets and check the padding tracks them.
        val activity = launchMain().get()
        val scroll = descendants(activity.findViewById(android.R.id.content))
            .filterIsInstance<ScrollView>().first()

        scroll.dispatchApplyWindowInsets(bars(0, 0))
        val baseTop = scroll.paddingTop
        val baseBottom = scroll.paddingBottom

        scroll.dispatchApplyWindowInsets(bars(100, 50))

        assertEquals("status bar inset not applied", baseTop + 100, scroll.paddingTop)
        assertEquals("navigation bar inset not applied", baseBottom + 50, scroll.paddingBottom)
    }

    @Test
    fun `insets replace rather than accumulate`() {
        // Adding to the current padding on every pass is the classic version
        // of this bug: the content creeps down the screen on every rotation,
        // keyboard open, or theme change.
        val activity = launchMain().get()
        val scroll = descendants(activity.findViewById(android.R.id.content))
            .filterIsInstance<ScrollView>().first()

        scroll.dispatchApplyWindowInsets(bars(100, 50))
        val once = scroll.paddingTop
        repeat(4) { scroll.dispatchApplyWindowInsets(bars(100, 50)) }

        assertEquals("padding grew on repeated inset passes", once, scroll.paddingTop)
    }

    @Test
    fun `the launcher screen names every surface`() {
        val body = texts(launchMain().get().findViewById(android.R.id.content))
        listOf("Text selection", "Quick Settings tile", "Home screen widget",
               "App shortcuts", "Share sheet").forEach { surface ->
            assertTrue("missing: $surface", body.any { it.contains(surface) })
        }
    }

    @Test
    fun `the launcher screen names the build it came from`() {
        // The iteration loop is: change it, push it, install it, look. That
        // last step needs something on screen to look at, or you cannot tell
        // a new APK from the one already on the phone.
        val body = texts(launchMain().get().findViewById(android.R.id.content))
        assertTrue("no build label on screen",
            body.any { it.startsWith("Build ") && it.length > "Build ".length })
    }

    @Test
    fun `the brain reports its state on the launcher screen`() {
        val body = texts(launchMain().get().findViewById(android.R.id.content))
        assertTrue("status line never resolved",
            body.none { it == "Checking..." })
    }

    // ------------------------------------------------------- text selection

    private fun processText(alias: String, selection: String, readOnly: Boolean) =
        Robolectric.buildActivity(
            ProcessTextActivity::class.java,
            Intent().apply {
                component = android.content.ComponentName(
                    "com.caceras.surfacelab", "com.caceras.surfacelab.$alias"
                )
                putExtra(Intent.EXTRA_PROCESS_TEXT, selection)
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
            }
        ).setup()

    @Test
    fun `an editable selection is replaced in place`() {
        val controller = processText("Uppercase", "make me loud", readOnly = false)
        val shadow = shadowOf(controller.get())
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        assertEquals(
            "MAKE ME LOUD",
            shadow.resultIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        )
    }

    @Test
    fun `an empty selection does not open anything`() {
        val controller = processText("Uppercase", "   ", readOnly = true)
        assertTrue("activity should have finished", controller.get().isFinishing)
    }

    @Test
    fun `Ask offers its suggestions instead of a blank box`() {
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            ?: run {
                processText("Ask", "some selected material", readOnly = true)
                org.robolectric.shadows.ShadowDialog.getLatestDialog()
            }
        requireNotNull(dialog) { "the Ask dialog never opened" }

        val root = dialog.window!!.decorView
        val buttons = descendants(root).filterIsInstance<Button>()
        Prompts.SUGGESTIONS.forEach { suggestion ->
            assertTrue(
                "suggestion not offered: $suggestion",
                buttons.any { it.text.toString() == suggestion }
            )
        }
    }

    @Test
    fun `tapping a suggestion fills the prompt box`() {
        processText("Ask", "some selected material", readOnly = true)
        val dialog = requireNotNull(
            org.robolectric.shadows.ShadowDialog.getLatestDialog()
        )
        val root = dialog.window!!.decorView
        val input = descendants(root).filterIsInstance<EditText>().first()
        val chip = descendants(root).filterIsInstance<Button>()
            .first { it.text.toString() == Prompts.SUGGESTIONS.first() }

        chip.performClick()

        assertEquals(Prompts.SUGGESTIONS.first(), input.text.toString())
    }

    @Test
    fun `the suggestion row scrolls rather than wrapping off screen`() {
        processText("Ask", "material", readOnly = true)
        val root = requireNotNull(
            org.robolectric.shadows.ShadowDialog.getLatestDialog()
        ).window!!.decorView
        val row = descendants(root)
            .filterIsInstance<android.widget.HorizontalScrollView>()
            .firstOrNull()
        assertTrue("suggestions are not in a horizontal scroller", row != null)
        assertTrue((row!!.getChildAt(0) as LinearLayout).childCount ==
            Prompts.SUGGESTIONS.size)
    }
}
