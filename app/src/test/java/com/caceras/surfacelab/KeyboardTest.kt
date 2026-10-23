package com.caceras.surfacelab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Ægentica keys: what it reads from a field, what it sends, and that it writes only when asked. */
@RunWith(AndroidJUnit4::class)
class KeyboardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun byTag(root: View, tag: String) = views(root).first { it.tag == tag }
    private fun chip(root: View, text: String) = views(root).first { it is TextView && it.text.toString() == text }

    private class StubBrain : SurfaceBrain {
        var task: Task? = null; var input = ""; var instruction = ""; var finish: ((BrainResult) -> Unit)? = null
        override val tasks = listOf(Task.KEYBOARD)
        override fun status(context: Context, onStatus: (BrainStatus) -> Unit) = onStatus(BrainStatus("Stub", true))
        override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) = status(context, onStatus)
        override fun run(context: Context, task: Task, input: String, instruction: String, onPartial: (String) -> Unit, onResult: (BrainResult) -> Unit) {
            this.task = task; this.input = input; this.instruction = instruction; finish = onResult
        }
        override fun look(context: Context, task: Task, input: String, instruction: String, image: Bitmap, onPartial: (String) -> Unit, onResult: (BrainResult) -> Unit) = run(context, task, input, instruction, onPartial, onResult)
    }

    private fun panel(field: EditText, type: Int = InputType.TYPE_CLASS_TEXT): KeyboardPanel {
        val info = EditorInfo().apply { inputType = type }
        val connection = field.onCreateInputConnection(EditorInfo())
        return KeyboardPanel(context, object : KeyboardPanel.Host {
            override fun connection() = connection
            override fun editor() = info
            override fun backToKeyboard() = Unit
        })
    }

    @After fun tearDown() { Brains.useForTest(null) }

    @Test fun `an action sends the whole field as marked data and replaces it only when asked`() {
        val brain = StubBrain(); Brains.useForTest(brain)
        val field = EditText(context).apply { setText("hej hur mår du"); setSelection(text.length) }
        val root = panel(field).view
        chip(root, "Fix").performClick(); idle()
        assertEquals(Task.KEYBOARD, brain.task)
        assertEquals("hej hur mår du", brain.input)
        assertTrue(Prompts.user(Task.KEYBOARD, brain.input, brain.instruction).contains("<<<\nhej hur mår du\n>>>"))
        brain.finish!!(BrainResult("Hej, hur mår du?", true)); idle()
        assertEquals("nothing is written before a tap", "hej hur mår du", field.text.toString())
        byTag(root, "keys-replace").performClick(); idle()
        assertEquals("Hej, hur mår du?", field.text.toString())
    }

    @Test fun `a selection is read and replaced on its own`() {
        val brain = StubBrain(); Brains.useForTest(brain)
        val field = EditText(context).apply { setText("Hello world, see you"); setSelection(0, 11) }
        val root = panel(field).view
        chip(root, "Translate").performClick(); idle()
        assertEquals("Hello world", brain.input)
        brain.finish!!(BrainResult("Hej världen", true)); idle()
        assertEquals("Replace selection", (byTag(root, "keys-replace") as TextView).text.toString())
        byTag(root, "keys-replace").performClick(); idle()
        assertEquals("Hej världen, see you", field.text.toString())
    }

    @Test fun `password fields are never read`() {
        val brain = StubBrain(); Brains.useForTest(brain)
        assertTrue(KeyboardText.secret(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }))
        assertFalse(KeyboardText.secret(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }))
        val field = EditText(context).apply { setText("hunter2") }
        val root = panel(field, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).view
        chip(root, "Improve").performClick(); idle()
        assertNull(brain.task)
        assertTrue((byTag(root, "keys-status") as TextView).text.contains("off in password fields"))
    }

    @Test fun `reply to copied answers the clipboard and inserts at the cursor`() {
        val brain = StubBrain(); Brains.useForTest(brain)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("m", "Kan du ringa mig i eftermiddag?"))
        val field = EditText(context).apply { setText("") }
        val root = panel(field).view
        byTag(root, "keys-reply").performClick(); idle()
        assertEquals("Kan du ringa mig i eftermiddag?", brain.input)
        brain.finish!!(BrainResult("Absolut, jag ringer vid tre.", true)); idle()
        assertNull("the clipboard is not a field, so there is nothing to replace", views(root).firstOrNull { it.tag == "keys-replace" })
        byTag(root, "keys-insert").performClick(); idle()
        assertEquals("Absolut, jag ringer vid tre.", field.text.toString())
    }
}
