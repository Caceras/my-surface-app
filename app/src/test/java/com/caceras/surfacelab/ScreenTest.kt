package com.caceras.surfacelab

import android.app.Activity
import android.os.Build
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer
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

    /**
     * The chat screen now restores its conversation from disk on launch, so
     * a test that counts bubbles is only counting its own if the store is
     * empty when it starts.
     */
    @org.junit.Before
    fun startWithNoConversation() {
        Chat.clear(org.robolectric.RuntimeEnvironment.getApplication())
    }

    private fun descendants(view: View): List<View> =
        if (view !is ViewGroup) listOf(view)
        else listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }

    private fun texts(root: View) =
        descendants(root).filterIsInstance<TextView>().map { it.text.toString() }

    /** A hidden view is still a descendant, so visibility has to be walked. */
    private fun showing(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node.visibility != View.VISIBLE) return false
            node = node.parent as? View
        }
        return true
    }

    private fun visibleTexts(root: View) =
        descendants(root).filterIsInstance<TextView>()
            .filter { showing(it) }
            .map { it.text.toString() }

    // ---------------------------------------------------------------- main

    private fun launchMain(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun content(activity: android.app.Activity): View =
        activity.findViewById(android.R.id.content)

    private fun root(activity: android.app.Activity): View =
        (content(activity) as ViewGroup).getChildAt(0)

    /** The composer's text field: the only EditText on the screen. */
    private fun composer(activity: android.app.Activity): EditText =
        descendants(content(activity)).filterIsInstance<EditText>().first()

    private fun button(activity: android.app.Activity, label: String) =
        descendants(content(activity))
            .filterIsInstance<android.widget.ImageButton>()
            .first { it.contentDescription == label }

    /** Bubbles live in the column inside the transcript scroller. */
    private fun bubbles(activity: android.app.Activity): List<String> {
        val scroll = descendants(content(activity))
            .filterIsInstance<ScrollView>().first()
        val column = scroll.getChildAt(0) as ViewGroup
        return (0 until column.childCount)
            .map { column.getChildAt(it) }
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
    }

    @Test
    fun `spacing is density-scaled, not raw pixels`() {
        val activity = launchMain().get()
        val header = (root(activity) as ViewGroup).getChildAt(0)

        val density = activity.resources.displayMetrics.density
        val expected = (20 * density).toInt()

        assertEquals("left padding is not dp-scaled", expected, header.paddingLeft)
        assertEquals("right padding is not dp-scaled", expected, header.paddingRight)
    }

    private fun bars(top: Int, bottom: Int) = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, top, 0, bottom))
        .build()

    @Test
    fun `the screen consumes the system bars`() {
        // Targeting SDK 35+ means edge-to-edge whether the app asks or not, so
        // without this the title sits under the status bar and the composer
        // under the navigation bar. Asserted by behaviour: hand it real
        // insets and check the padding tracks them.
        val activity = launchMain().get()
        val view = root(activity)

        view.dispatchApplyWindowInsets(bars(0, 0))
        val baseTop = view.paddingTop
        val baseBottom = view.paddingBottom

        view.dispatchApplyWindowInsets(bars(100, 50))

        assertEquals("status bar inset not applied", baseTop + 100, view.paddingTop)
        assertEquals("navigation bar inset not applied", baseBottom + 50, view.paddingBottom)
    }

    @Test
    fun `insets replace rather than accumulate`() {
        // Adding to the current padding on every pass is the classic version
        // of this bug: the content creeps down the screen on every rotation,
        // keyboard open, or theme change.
        val activity = launchMain().get()
        val view = root(activity)

        view.dispatchApplyWindowInsets(bars(100, 50))
        val once = view.paddingTop
        repeat(4) { view.dispatchApplyWindowInsets(bars(100, 50)) }

        assertEquals("padding grew on repeated inset passes", once, view.paddingTop)
    }

    @Test
    fun `the screen opens on a conversation, not a form`() {
        // The regression this guards against is the screen drifting back
        // into a list of surfaces with a prompt box bolted on. What should
        // be visible first is somewhere to type and a way to send.
        val activity = launchMain().get()
        assertTrue("no composer on screen",
            descendants(content(activity)).filterIsInstance<EditText>().isNotEmpty())
        assertEquals("the conversation did not start empty", 0, bubbles(activity).size)

        val body = visibleTexts(content(activity))
        listOf("Quick Settings tile", "Home screen widget", "Share sheet").forEach { entry ->
            assertTrue("$entry should be behind More, not on the chat screen",
                body.none { line -> line.contains(entry) })
        }
    }

    @Test
    fun `sending a message puts both sides in the conversation`() {
        val activity = launchMain().get()
        composer(activity).setText("hello there")
        button(activity, activity.getString(R.string.send)).performClick()

        val said = bubbles(activity)
        assertEquals("expected a question and an answer", 2, said.size)
        assertEquals("hello there", said[0])
        // The core brain is a deterministic echo, which is the point of it.
        assertEquals("HELLO THERE", said[1])
        assertEquals("the composer was not cleared", "", composer(activity).text.toString())
    }

    @Test
    fun `an empty message sends nothing`() {
        val activity = launchMain().get()
        composer(activity).setText("   ")
        button(activity, activity.getString(R.string.send)).performClick()
        assertEquals(0, bubbles(activity).size)
    }

    @Test
    fun `openers fill the composer and step aside once talking has started`() {
        val activity = launchMain().get()
        val opener = descendants(content(activity))
            .filterIsInstance<TextView>()
            .first { it.text.toString() == Prompts.OPENERS.first() }

        opener.performClick()
        assertTrue("opener did not reach the composer",
            composer(activity).text.toString().startsWith(Prompts.OPENERS.first()))

        composer(activity).setText("something")
        button(activity, activity.getString(R.string.send)).performClick()

        val row = descendants(content(activity))
            .filterIsInstance<android.widget.HorizontalScrollView>().first()
        assertEquals("openers still showing mid-conversation", View.GONE, row.visibility)
    }

    private fun mics(activity: android.app.Activity) =
        descendants(content(activity))
            .filterIsInstance<android.widget.ImageButton>()
            .filter { it.contentDescription == activity.getString(R.string.mic) }

    @Test
    fun `no microphone is offered when speech cannot stay on the device`() {
        // Never a button that quietly ships audio somewhere: without an
        // on-device recogniser there is no microphone at all. Set both ways
        // explicitly rather than trusting a default -- the first version of
        // this test assumed the default was false, and it is not.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val activity = launchMain().get()
        assertTrue("a mic was offered with no on-device recogniser",
            mics(activity).isEmpty())
    }

    @Test
    fun `the microphone appears when speech can stay on the device`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val activity = launchMain().get()
        assertEquals("expected exactly one mic", 1, mics(activity).size)
    }

    @Test
    fun `the reference material is one tap away, not in the way`() {
        val activity = launchMain().get()
        val more = descendants(content(activity))
            .filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(R.string.more) }

        more.performClick()

        val body = visibleTexts(content(activity))
        listOf("Text selection", "Quick Settings tile", "Home screen widget",
               "App shortcuts", "Share sheet").forEach { surface ->
            assertTrue("missing after More: $surface", body.any { it.contains(surface) })
        }
        assertTrue("no build label behind More",
            body.any { it.startsWith("Build ") && it.length > "Build ".length })
    }

    @Test
    fun `the brain reports its state on the chat screen`() {
        val body = texts(content(launchMain().get()))
        assertTrue("status line never resolved",
            body.none { it == "Working on device\u2026" })
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
        Prompts.ABOUT_SELECTION.forEach { suggestion ->
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
            .first { it.text.toString() == Prompts.ABOUT_SELECTION.first() }

        chip.performClick()

        assertEquals(Prompts.ABOUT_SELECTION.first(), input.text.toString())
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
            Prompts.ABOUT_SELECTION.size)
    }
}
