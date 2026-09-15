package com.caceras.surfacelab

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.provider.AlarmClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
class NativeFeaturesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("surfacelab", 0).edit().clear().commit() }
    @After fun reset() { Brains.useForTest(null) }
    private fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun tap(root: View, text: String) = children(root).filterIsInstance<TextView>().first { it.text == text }.performClick()

    @Test fun `actions never execute arbitrary model text and validate clock values`() {
        assertThrows(IllegalArgumentException::class.java) { NativeActions.timer(0) }
        assertThrows(IllegalArgumentException::class.java) { NativeActions.timer(1441) }
        val timer = NativeActions.timer(15)
        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(900, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertFalse(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        assertThrows(IllegalArgumentException::class.java) { NativeActions.alarm(25, 0) }
        assertThrows(IllegalArgumentException::class.java) { NativeActions.dial("run#command") }
        assertEquals(Intent.ACTION_DIAL, NativeActions.dial("+46 70 123 45 67").action)
        assertEquals("geo:0,0?q=coffee%20%26%20cake", NativeActions.maps("coffee & cake").data.toString())
        assertEquals(Intent.ACTION_INSERT, NativeActions.calendar("A thought").action)
    }

    @Test fun `timer form rejects invalid input and opens a visible native handoff`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sheet = NativeActions.show(activity)
        tap(sheet.window!!.decorView, "Set a timer")
        val form = ShadowDialog.getLatestDialog() as AlertDialog
        val field = children(form.window!!.decorView).filterIsInstance<EditText>().single()
        field.setText("0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(form.isShowing)
        assertNotNull(field.error)
        field.setText("5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(AlarmClock.ACTION_SET_TIMER, shadowOf(activity).nextStartedActivity.action)
        assertFalse(form.isShowing)
        assertFalse(sheet.isShowing)
    }

    @Test fun `shortcuts stage navigation without sending the current draft`() {
        Chat.saveDraft(context, "A private unfinished thought")
        val brain = StreamingBrain(); Brains.useForTest(brain)
        val activity = Robolectric.buildActivity(MainActivity::class.java,
            Intent(context, MainActivity::class.java).setAction(NativeShortcuts.ACTIONS)).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertEquals(0, brain.runs)
        assertEquals("A private unfinished thought", children(activity.window.decorView).filterIsInstance<EditText>().first().text.toString())
    }

    @Test fun `control N archives the draft and control enter sends only once`() {
        val brain = StreamingBrain(); Brains.useForTest(brain)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = children(activity.window.decorView).filterIsInstance<EditText>().first()
        field.setText("Remember this thought")
        fun key(code: Int) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON)
        activity.onKeyShortcut(KeyEvent.KEYCODE_N, key(KeyEvent.KEYCODE_N))
        assertEquals("Remember this thought", Chat.archives(activity).single().draft)
        field.setText("Explain this")
        activity.onKeyShortcut(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        activity.onKeyShortcut(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(1, brain.runs)
    }

    @Test fun `voice keyboard shortcut routes through activity dispatch without stealing paste`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = children(activity.window.decorView).filterIsInstance<EditText>().first()
        field.requestFocus()
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertTrue(activity.dispatchKeyShortcutEvent(event))
        assertEquals(VoiceActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertFalse(activity.onKeyShortcut(KeyEvent.KEYCODE_V, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON)))
    }

    @Test fun `widget answers are opt in and compact controls stay available`() {
        ResultStore.save(context, Task.ASK, "Private answer")
        val provider = SurfaceWidgetProvider()
        fun render(compact: Boolean) = provider.views(context, compact).apply(context, FrameLayout(context))
        assertFalse(children(render(false)).filterIsInstance<TextView>().any { it.text.contains("Private answer") })
        NativePrivacy.setWidgetPreview(context, true)
        assertTrue(children(render(false)).filterIsInstance<TextView>().any { it.text.contains("Private answer") })
        val compact = render(true)
        assertNotNull(compact.findViewById<View>(R.id.widget_type))
        assertNotNull(compact.findViewById<View>(R.id.widget_talk))
        assertNull(compact.findViewById<View>(R.id.widget_value))
    }

    @Test fun `private mode protects activities and action dialogs and clipboard hides its preview`() {
        NativePrivacy.setPrivateScreen(context, true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val sheet = NativeActions.show(activity)
        assertTrue(sheet.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        tap(sheet.window!!.decorView, "Set a timer")
        assertTrue(ShadowDialog.getLatestDialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        NativePrivacy.copy(activity, "Answer", "A private thought")
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals("A private thought", clip.getItemAt(0).text)
    }

    @Test fun `media pause and unplug stop only an active session`() {
        val messages = mutableListOf<String>()
        val controls = SpeechControls(context) { messages.add(it) }
        controls.callback.onPause()
        assertTrue(messages.isEmpty())
        controls.playing(); controls.callback.onPause()
        assertEquals(1, messages.size)
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(messages.last().contains("Headphones"))
        controls.stopped()
        val previous = messages.size
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(previous, messages.size)
        controls.close()
    }
}
