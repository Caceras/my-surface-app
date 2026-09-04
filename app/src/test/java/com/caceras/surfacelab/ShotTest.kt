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
 *     gradle testCoreDebugUnitTest     # writes app/build/screenshots/*.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class ShotTest {

    private val width = 1233   // 411dp at xxhdpi, a Pixel in portrait
    private val height = 2742

    private fun shoot(name: String, decor: View) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)   // so "drew nothing" is unmistakable
        decor.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // A screen that drew nothing is a screenshot of the erase colour.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val distinct = pixels.toHashSet()
        assertTrue("$name drew nothing at all", distinct.size > 1)
        assertTrue("$name is still the erase colour -- nothing was painted",
            distinct.size > 2 || !distinct.contains(Color.MAGENTA))
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
        val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
        shoot("voice", activity.window.decorView)
    }
}
