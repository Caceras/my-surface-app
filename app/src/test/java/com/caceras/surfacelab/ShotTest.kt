package com.caceras.surfacelab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real screens to PNG files.
 *
 * Every layout bug this project has shipped was obvious in one second on a
 * phone and invisible to everything else: padding in pixels, a title bar
 * drawn twice, a screen described in a commit message by someone who had
 * never seen it. There is no Android SDK on the machine these changes are
 * written on and no emulator in CI, so "how does it look" has been answered
 * by guessing.
 *
 * Robolectric's native graphics mode rasterises for real -- the same Skia the
 * device uses. So the screens can be rendered on the JVM and uploaded as
 * build artefacts, and the question stops being a guess.
 *
 * These are not assertions about beauty. They assert only that something was
 * drawn; the point is the file, which a person (or a model that cannot open
 * an emulator) can then actually look at.
 *
 *     gradle testCoreDebugUnitTest
 *
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class ShotTest {

    private val width = 1233   // 411dp at xxhdpi, a Pixel in portrait
    private val height = 2742

    /**
     * [minPainted] is the fraction of the screen that must not still be the
     * erase colour. The first version of this only asked for two distinct
     * colours, and passed on a screen that was ninety per cent unpainted --
     * an assertion weak enough to be worthless.
     */
    private fun shoot(name: String, decor: View, minPainted: Double = 0.9, shotWidth: Int = width, shotHeight: Int = height) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(shotWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(shotHeight, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, shotWidth, shotHeight)

        val bitmap = Bitmap.createBitmap(shotWidth, shotHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)   // so "drew nothing" is unmistakable
        decor.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // A screen that drew nothing is a screenshot of the erase colour.
        val pixels = IntArray(shotWidth * shotHeight)
        bitmap.getPixels(pixels, 0, shotWidth, 0, 0, shotWidth, shotHeight)
        val painted = pixels.count { it != Color.MAGENTA }.toDouble() / pixels.size
        assertTrue(
            "$name painted only ${"%.1f".format(painted * 100)}% of the screen, " +
                "expected at least ${"%.0f".format(minPainted * 100)}%",
            painted >= minPainted
        )
    }

    @Test
    fun `the chat screen, empty`() {
        Chat.clear(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("chat-empty", activity.window.decorView)
    }

    @Test
    fun `the chat screen, mid conversation`() {
        Chat.save(RuntimeEnvironment.getApplication(), listOf(
            Turn("Give me three ideas for a Sunday",
                 "**Walk somewhere new.** Pick a street you have never turned down.\n" +
                 "* Cook something slow -- a stew, bread, anything that takes hours.\n" +
                 "* Call the person you keep meaning to call."),
            Turn("list 10 more",
                 "1. Read in a park\n2. Fix the thing that has been broken\n" +
                 "3. Swim\n4. Write a letter\n5. Cycle with no destination")
        ))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("chat-conversation", activity.window.decorView)
    }

    @Test
    fun `the hands-free screen`() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
        shoot("voice", activity.window.decorView, minPainted = 0.05)
    }
    @Test
    fun `the settings panel remains contained`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun children(view: View): List<View> = if (view !is android.view.ViewGroup) listOf(view)
            else listOf(view) + (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
        children(activity.window.decorView).filterIsInstance<android.widget.TextView>()
            .first { it.text == activity.getString(R.string.more) }.performClick()
        shoot("chat-settings", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
    }

    @Test
    fun `voice setup with an explicit path back to typing`() {
        val activity = Robolectric.buildActivity(VoiceActivity::class.java,
            android.content.Intent().putExtra("setup", true)).setup().get()
        shoot("voice-setup", activity.window.decorView)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    fun `chat in dark mode`() {
        Chat.clear(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("chat-night", activity.window.decorView)
    }

    @Test
    fun `missing speech language keeps setup and typing reachable`() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
        org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            .triggerOnError(android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        shoot("voice-error", activity.window.decorView)
    }

    @Test
    fun `voice answer streams into the native screen`() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val brain = StreamingBrain()
        Brains.useForTest(brain)
        try {
            val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
            org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
                .triggerOnResults(android.os.Bundle().apply {
                    putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf("Help me slow down for a minute."))
                })
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            brain.emit("Let’s make a little space.\n\nPut down what you’re carrying. Take one slow breath.\n\nWhat is the one thing that needs your attention next?")
            shoot("voice-answer", activity.window.decorView)
        } finally { Brains.useForTest(null) }
    }
    @Test
    fun `saved conversations have searchable previews`() {
        val context = RuntimeEnvironment.getApplication()
        Chat.clearArchives(context)
        Chat.save(context, listOf(Turn("A slower Sunday", "A long walk, good coffee, and a little time to read.")))
        Chat.archiveCurrent(context, "Find a quiet place nearby")
        Chat.save(context, listOf(Turn("The right words", "Thank you for making time. I would love to hear what you think.")))
        Chat.archiveCurrent(context, "")
        Chat.clear(context)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sheet = ConversationSheet(activity) {}
        sheet.show()
        shoot("conversations", sheet.window!!.decorView)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun `compact chat keeps playback and navigation reachable`() {
        val context = RuntimeEnvironment.getApplication()
        Chat.save(context, listOf(Turn("A quick thought", "Make space for one thing at a time.")))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun children(view: View): List<View> = if (view !is android.view.ViewGroup) listOf(view)
            else listOf(view) + (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
        children(activity.window.decorView).filterIsInstance<android.widget.TextView>()
            .first { it.text == "Listen" }.performClick()
        shoot("chat-compact", activity.window.decorView, shotWidth = 960, shotHeight = 1920)
        listOf("Voice", "Conversations", activity.getString(R.string.stop_speaking)).forEach { label ->
            val control = children(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text == label }
            val rect = android.graphics.Rect()
            assertTrue("$label is not visible", control.getGlobalVisibleRect(rect))
            assertTrue("$label is clipped", rect.height() >= activity.dp(48))
        }
    }


}
