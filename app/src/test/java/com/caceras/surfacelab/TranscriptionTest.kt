package com.caceras.surfacelab

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/** Transcription-first capture, verbatim preservation, polish review and recall. */
@RunWith(AndroidJUnit4::class)
class TranscriptionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        context.deleteDatabase("aegentica.db")
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        TranscriptionService.clearFinished()
    }
    @After fun tearDown() { ShadowSpeechRecognizer.reset(); KnowledgeContext.setRecall(context, true); context.deleteDatabase("aegentica.db") }

    private fun recognizer() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    private fun said(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun service(action: String? = null, controller: ServiceController<TranscriptionService>? = null): ServiceController<TranscriptionService> {
        val running = controller ?: Robolectric.buildService(TranscriptionService::class.java).create()
        running.get().onStartCommand(Intent(context, TranscriptionService::class.java).setAction(action), 0, 1)
        idle()
        return running
    }

    @Test fun `verbatim lines survive polish and continue in a linked part when a transcript fills`() {
        WorkspaceStore(context).use { store ->
            val first = Transcripts.append(store, null, 0, 1, 3_000, "hej det här är", "sv-SE")
            assertEquals("[00:03] hej det här är", first.original)
            val second = Transcripts.append(store, first.id, 0, 1, 65_000, "andra raden", "sv-SE")
            assertEquals(first.id, second.id)
            val polished = store.save(second.copy(body = "Hej, det här är andra raden."), second.revision)
            assertEquals("[00:03] hej det här är\n[01:05] andra raden", polished.original)
            val third = Transcripts.append(store, first.id, 0, 1, 3_700_000, "tredje", "sv-SE")
            assertTrue(third.original.endsWith("[1:01:40] tredje")); assertTrue(third.body.startsWith("Hej, det här är andra raden."))
            val part = Transcripts.append(store, first.id, 0, 1, 3_800_000, "x".repeat(40), "sv-SE", limit = 80)
            assertNotEquals(first.id, part.id); assertTrue(part.title.endsWith("part 2"))
            assertEquals(first.id, store.linked(part.id).single().id)
            assertEquals("hej det här är\nandra raden", Transcripts.words(polished.original))
        }
    }

    @Test fun `continuous transcription saves each segment, keeps listening and stops with the last words`() {
        val controller = service()
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertTrue(TranscriptionService.state.active)
        recognizer().triggerOnPartialResults(said("hej"))
        assertEquals("hej", TranscriptionService.state.partial)
        recognizer().triggerOnResults(said("hej världen"))
        idle(PlatformSpeechEngine.RESTART_MS + 50)
        assertNotSame("Android ends a session at each pause; the next one must start", first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        val id = TranscriptionService.state.record
        val saved = WorkspaceStore(context).use { it.get(id)!! }
        assertTrue(Transcripts.isTranscript(saved)); assertTrue(saved.original.endsWith("hej världen"))
        // Silence after a normal listening period is not a failure and quietly restarts.
        idle(5_000)
        recognizer().triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); idle(PlatformSpeechEngine.RESTART_MS + 50)
        assertFalse(TranscriptionService.state.paused)
        recognizer().triggerOnPartialResults(said("sista orden"))
        service(TranscriptionService.STOP, controller)
        idle(PlatformSpeechEngine.FINISH_MS + 100)
        assertFalse(TranscriptionService.state.active)
        assertEquals(id, TranscriptionService.state.record)
        assertTrue(WorkspaceStore(context).use { it.get(id)!!.original }.endsWith("sista orden"))
        assertTrue(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed)
        controller.destroy()
    }

    @Test fun `a busy microphone pauses visibly instead of retrying forever and resumes only on request`() {
        val controller = service()
        repeat(PlatformSpeechEngine.MAX_FAILURES) { recognizer().triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); idle(7000) }
        assertTrue(TranscriptionService.state.paused)
        assertTrue(TranscriptionService.state.message.contains("busy"))
        val last = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idle(60_000)
        assertSame("No hidden retries after pausing", last, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        service(TranscriptionService.RESUME, controller)
        assertFalse(TranscriptionService.state.paused)
        assertNotSame(last, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        controller.destroy()
        assertFalse(TranscriptionService.state.active)
    }

    @Test fun `dictating elsewhere pauses transcription and never resumes it automatically`() {
        val controller = service()
        Ears(context).listen()
        idle(PlatformSpeechEngine.FINISH_MS + 100)
        assertTrue(TranscriptionService.state.paused)
        val dictation = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idle(30_000)
        assertSame(dictation, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertTrue(TranscriptionService.state.paused)
        controller.destroy()
    }

    @Test fun `transcribe screen starts the service and launcher shortcuts reach their destinations`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val screen = Robolectric.buildActivity(TranscribeActivity::class.java, TranscribeActivity.intent(context, start = true)).setup()
        assertEquals(TranscriptionService::class.java.name, shadowOf(context).nextStartedService.component?.className)
        screen.pause().stop().destroy()
        for ((action, target) in listOf(NativeShortcuts.TRANSCRIBE to TranscribeActivity::class.java, NativeShortcuts.CAPTURE to WorkspaceActivity::class.java)) {
            val home = Robolectric.buildActivity(HomeActivity::class.java, Intent(context, HomeActivity::class.java).setAction(action)).setup()
            val next = shadowOf(home.get()).nextStartedActivity
            assertEquals(target.name, next.component?.className)
            assertTrue(next.getBooleanExtra(if (action == NativeShortcuts.TRANSCRIBE) TranscribeActivity.START else "capture", false))
            home.pause().stop().destroy()
        }
        val tasks = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.intent(context, "tasks").putExtra(WorkspaceActivity.NEW_TASK, true)).setup()
        assertTrue(views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().any { it.text == "Task" })
        tasks.pause().stop().destroy()
    }

    @Test fun `polish replaces only the working text after review and keeps the verbatim original`() {
        val id = WorkspaceStore(context).use { Transcripts.append(it, null, 0, 1, 1_000, "eh så vi ses på fredag", "sv-SE").id }
        val activity = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.intent(context, "library", id)).setup()
        val editor = ShadowDialog.getLatestDialog()
        views(editor.window!!.decorView).filterIsInstance<TextView>().first { it.text == "Polish" }.performClick()
        idle()
        val preview = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(views(preview.window!!.decorView).filterIsInstance<TextView>().any { it.tag == "polish-preview" && it.text.contains("FREDAG") })
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle(400)
        val saved = WorkspaceStore(context).use { it.get(id)!! }
        assertEquals("EH SÅ VI SES PÅ FREDAG", saved.body)
        assertEquals("[00:01] eh så vi ses på fredag", saved.original)
        assertEquals("EH SÅ VI SES PÅ FREDAG", views(editor.window!!.decorView).filterIsInstance<EditText>().first { it.tag == "note-body" }.text.toString())
        activity.pause().stop().destroy()
    }

    @Test fun `polish parts keep every word within the model budget`() {
        val text = (1..400).joinToString(" ") { "ord$it." } + "\n\nkort rad\n" + "x".repeat(700)
        val parts = Polish.chunks(text, 300)
        assertTrue(parts.all { it.length <= 300 })
        assertEquals(text.split(Regex("\\s+")).joinToString(""), parts.joinToString("").split(Regex("\\s+")).joinToString(""))
    }

    @Test fun `recall cites the user's own words and respects the connected opt-in`() {
        WorkspaceStore(context).use { store ->
            Transcripts.append(store, null, 0, 1, 12_000, "vi bestämde att lansera onboarding på fredag", "sv-SE")
            store.save(Record(title = "Inköp", body = "mjölk och bröd"))
            store.save(Record(title = "Gammal", body = "onboarding plan B", deleted = true))
            store.save(Record(kind = "routine", body = "onboarding rutin"))
        }
        val prompt = KnowledgeContext.prompt(context, "Vad bestämde vi om onboarding?", remote = false)
        assertTrue(prompt.contains("lansera onboarding på fredag")); assertTrue(prompt.contains("[1]"))
        assertFalse(prompt.contains("mjölk")); assertFalse(prompt.contains("plan B")); assertFalse(prompt.contains("rutin"))
        assertEquals(1, KnowledgeContext.lastRecalled.size)
        assertFalse("Connected providers need their own opt-in", KnowledgeContext.prompt(context, "Vad bestämde vi om onboarding?", remote = true).contains("lansera"))
        KnowledgeContext.setRecall(context, false)
        assertFalse(KnowledgeContext.prompt(context, "Vad bestämde vi om onboarding?", remote = false).contains("lansera"))
    }

    @Test fun `markdown vault keeps links, typed fields and the verbatim original`() {
        val note = Record(id = "11111111-aaaa", title = "Möte: plan/idé", body = "Hej, polerad.", original = "[00:01] hej polerad", source = "Transcript · sv-SE · on this phone")
        val person = Record(id = "22222222-bbbb", kind = "person", title = "Lina")
        val md = MarkdownVault.render(note, listOf(person to "related"), listOf("Status" to "Klar"))
        assertEquals("Möte plan idé (11111111).md", MarkdownVault.fileName(note))
        assertTrue(md.startsWith("---\nid: \"11111111-aaaa\"\nkind: transcript\n"))
        assertTrue(md.contains("  - \"[[Lina (22222222)]]\"")); assertTrue(md.contains("\"Status\": \"Klar\""))
        assertTrue(md.contains("# Möte: plan/idé\n\nHej, polerad.\n"))
        assertTrue(md.contains("## Verbatim original\n\n```text\n[00:01] hej polerad\n```"))
    }

    @Test fun `Gemini requests leave room for reasoning and polish keeps its own instruction`() {
        val gemini = ConnectedAI.body(ConnectedAI.GEMINI_MODEL, Prompts.system(Task.POLISH), "eh hej", ConnectedAI.gemini(ConnectedAI.GEMINI_ENDPOINT))
        assertEquals("low", gemini.getString("reasoning_effort"))
        assertTrue(gemini.getInt("max_completion_tokens") >= 4096)
        assertTrue(gemini.getJSONArray("messages").getJSONObject(0).getString("content").contains("dictated"))
        assertFalse(ConnectedAI.body("m", "s", "p", ConnectedAI.gemini("https://api.example.com/v1/chat/completions")).has("reasoning_effort"))
        ConnectedAI.validateEndpoint(ConnectedAI.GEMINI_ENDPOINT)
    }
}
