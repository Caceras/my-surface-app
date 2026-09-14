package com.caceras.surfacelab

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(AndroidJUnit4::class)
class CoherenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun tap(v: View, text: String) = children(v).filterIsInstance<TextView>().first { it.text == text }.performClick()
    @Before fun clear() { context.getSharedPreferences("surfacelab", 0).edit().clear().commit() }
    @After fun reset() { Brains.useForTest(null) }

    @Test fun `every navigation sheet stops hidden generation and preserves the question`() {
        for (entry in listOf("Settings", "History", "Actions")) {
            val brain = StreamingBrain(); Brains.useForTest(brain)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val root = activity.window.decorView
            val field = children(root).filterIsInstance<EditText>().first()
            field.setText("Keep this question")
            children(root).first { it.contentDescription == activity.getString(R.string.send) }.performClick()
            brain.emit("Partial reply")
            tap(root, entry)
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
            assertEquals("Keep this question", field.text.toString())
            assertEquals("Keep this question", Chat.draft(activity))
            brain.complete("This stale answer must not be saved")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Chat.load(activity).isEmpty())
            controller.pause().stop().destroy()
        }
    }

    @Test fun `history cancels dictation without losing reviewed words`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val field = children(root).filterIsInstance<EditText>().first()
        field.setText("My existing thought")
        children(root).first { it.contentDescription == activity.getString(R.string.mic) }.performClick()
        tap(root, "History")
        assertEquals("My existing thought", Chat.draft(activity))
        assertEquals(activity.getString(R.string.chat_hint), field.hint)
        assertFalse(children(root).any { it.contentDescription == activity.getString(R.string.finish_dictation) })
    }

    @Test fun `history has a reachable close action and private search editor`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sheet = ConversationSheet(activity) {}; sheet.show()
        val root = sheet.window!!.decorView
        val search = root.findViewWithTag<EditText>("conversation-search")
        assertTrue(search.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        tap(root, "Done")
        assertFalse(sheet.isShowing)
    }

    @Test @Config(qualifiers = "night")
    fun `settings switches share theme text colors and native semantics`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        tap(activity.window.decorView, "Settings")
        val switches = children(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<android.widget.Switch>()
        assertEquals(3, switches.size)
        switches.forEach { assertEquals(activity.getColor(R.color.text_primary), it.currentTextColor); assertTrue(it.minHeight >= activity.dp(48)) }
    }
}
