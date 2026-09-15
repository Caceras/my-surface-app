package com.caceras.surfacelab

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(AndroidJUnit4::class)
class DeepAuditTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val brain = StreamingBrain()
    private fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun tap(v: View, text: String) = children(v).filterIsInstance<TextView>().first { it.text == text }.performClick()
    private fun field(v: View) = children(v).filterIsInstance<EditText>().first()
    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog
    @Before fun setup() {
        context.getSharedPreferences("surfacelab", 0).edit().clear().commit()
        Brains.useForTest(brain)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }
    @After fun reset() { Brains.useForTest(null) }
    private fun selection() = Robolectric.buildActivity(ProcessTextActivity::class.java, Intent().apply {
        component = ComponentName(context.packageName, "com.caceras.surfacelab.Ask")
        putExtra(Intent.EXTRA_PROCESS_TEXT, "Material to discuss")
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }).setup()
    private fun spoken(text: String) {
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        })
    }

    @Test fun `selection blank send stays open and private then submits reviewed input once`() {
        selection()
        val dialog = latest()
        val input = field(dialog.window!!.decorView)
        assertTrue(input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertNotNull(input.error); assertEquals(0, brain.runs)
        input.setText("Explain the key idea")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, brain.runs)
        assertFalse(dialog.isShowing)
    }

    @Test fun `selection recreation keeps the prompt cursor and microphone off`() {
        val controller = selection()
        field(latest().window!!.decorView).apply { setText("My selected text question"); setSelection(3) }
        controller.recreate()
        val input = field(latest().window!!.decorView)
        assertEquals("My selected text question", input.text.toString())
        assertEquals(3, input.selectionStart)
        assertEquals(0, brain.runs)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test fun `voice recreation restores the question without reopening capture`() {
        val controller = Robolectric.buildActivity(VoiceActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        spoken("Remember this question")
        brain.emit("Some text so far")
        val old = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(old, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals(1, brain.runs)
        assertTrue(children(controller.get().window.decorView).filterIsInstance<TextView>().any { it.text == "Remember this question" })
        assertEquals("Remember this question", Chat.draft(context))
    }

    @Test fun `voice failure disables continuous mode and saves a typed recovery`() {
        val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        children(activity.window.decorView).filterIsInstance<android.widget.Switch>().single().isChecked = true
        spoken("Try this again")
        brain.fail("Model unavailable")
        assertEquals("Try this again", Chat.draft(context))
        assertFalse(children(activity.window.decorView).filterIsInstance<android.widget.Switch>().single().isChecked)
        assertTrue(Chat.load(context).isEmpty())
    }

    @Test fun `voice echo recovery does not replace a different typed draft`() {
        Chat.saveDraft(context, "An unfinished typed message")
        Robolectric.buildActivity(VoiceActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        spoken("Try this again")
        brain.complete(Prompts.system(Task.ASK))
        assertEquals("An unfinished typed message", Chat.draft(context))
        assertTrue(Chat.load(context).isEmpty())
    }

    @Test fun `editing an old failure requires a choice before replacing a newer draft`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val input = field(activity.window.decorView)
        input.setText("Original question")
        children(activity.window.decorView).first { it.contentDescription == activity.getString(R.string.send) }.performClick()
        brain.fail("Unavailable")
        input.setText("New draft")
        tap(activity.window.decorView, "Edit question")
        assertEquals("New draft", input.text.toString())
        latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("New draft", input.text.toString())
        tap(activity.window.decorView, "Edit question")
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Original question", input.text.toString())
        assertEquals(1, brain.runs)
    }

    private fun archive(id: String, question: String) = JSONObject().put("id", id).put("title", question).put("savedAt", 1L)
        .put("draft", "").put("turns", JSONArray().put(JSONObject().put("q", question).put("a", "Answer")))
    private fun backup(rows: JSONArray) = JSONObject().put("format", "surface-chat-v1").put("turns", JSONArray()).put("conversations", rows).toString()

    @Test fun `import rejects empty or duplicate archive identities without changing history`() {
        for (rows in listOf(JSONArray().put(archive("", "One")), JSONArray().put(archive("same", "One")).put(archive("same", "Two")))) {
            assertThrows(IllegalArgumentException::class.java) { Chat.readBackup(backup(rows)) }
        }
        assertTrue(Chat.archives(context).isEmpty())
    }

    @Test fun `import collision preserves both conversations with independent actions`() {
        Chat.restoreArchives(context, backup(JSONArray().put(archive("same", "Existing"))))
        Chat.restoreArchives(context, backup(JSONArray().put(archive("same", "Imported"))))
        val saved = Chat.archives(context)
        assertEquals(2, saved.size)
        assertEquals(2, saved.map { it.id }.distinct().size)
        Chat.deleteArchive(context, saved.first().id)
        assertEquals("Existing", Chat.archives(context).single().title)
    }
    @Test fun `calendar editor states and enforces its actual handoff limit`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sheet = NativeActions.show(activity)
        tap(sheet.window!!.decorView, "Draft a calendar event")
        val form = latest()
        val input = field(form.window!!.decorView)
        input.setText("x".repeat(250))
        assertEquals(200, input.length())
        assertTrue(children(form.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("200 characters") })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(input.text.toString(), shadowOf(activity).nextStartedActivity.getStringExtra(android.provider.CalendarContract.Events.TITLE))
    }

    @Test fun `dictation scale stays neutral when animations are disabled`() {
        val view = View(context)
        org.robolectric.shadows.ShadowUiAutomation.setAnimationScaleCompat(0f)
        try { view.speechLevel(10f); assertEquals(1f, view.scaleX, 0f); assertEquals(1f, view.scaleY, 0f) }
        finally { org.robolectric.shadows.ShadowUiAutomation.setAnimationScaleCompat(1f) }
    }

    @Test fun `Swedish replies stay intact without heuristic language disclaimers`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        field(activity.window.decorView).setText("Vad tycker du om Malmö?")
        children(activity.window.decorView).first { it.contentDescription == activity.getString(R.string.send) }.performClick()
        brain.complete("Det är fint. Här är ett förslag.")
        assertEquals("Det är fint. Här är ett förslag.", Chat.load(context).single().reply)
        val texts = children(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Det är fint. Här är ett förslag."))
        assertFalse(texts.any { it.contains("trained mostly") || it.contains("looks like Swedish") })
    }

    @Test fun `legacy stored ID collisions are repaired once without hiding history`() {
        val raw = JSONArray().put(archive("same", "One")).put(archive("same", "Two")).put(archive("", "Three"))
        context.getSharedPreferences("surfacelab", 0).edit().putString("conversations", raw.toString()).commit()
        val saved = Chat.archives(context)
        assertEquals(3, saved.size)
        assertEquals(3, saved.map { it.id }.distinct().size)
        assertTrue(saved.none { it.id.isBlank() })
        assertEquals(saved, Chat.archives(context))
        Chat.readBackup(Chat.backup(context))
    }

}
