package com.caceras.surfacelab

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration

/** Audio as the original: recording parts, Gemini transcription with speakers, renaming and share-in. */
@RunWith(AndroidJUnit4::class)
class AudioTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    /** Scripted Gemini endpoints; records every request. */
    class FakeGemini(private val speakers: Boolean = true, private val failInteractions: Int = 0, private val gate: java.util.concurrent.CountDownLatch? = null) : GeminiTranscribe.Http {
        val calls = mutableListOf<Triple<String, String, String>>()
        private var uploads = 0
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, file: File?): GeminiTranscribe.Response {
            calls += Triple(method, url, body?.toString(Charsets.UTF_8) ?: file?.name.orEmpty())
            return when {
                url.endsWith("/upload/v1beta/files") -> { assertEquals("resumable", headers["X-Goog-Upload-Protocol"]); GeminiTranscribe.Response(200, mapOf("X-Goog-Upload-URL" to "https://upload.example/session$uploads"), "") }
                url.startsWith("https://upload.example/") -> { uploads++; GeminiTranscribe.Response(200, emptyMap(), """{"file":{"name":"files/f$uploads","uri":"https://generativelanguage.googleapis.com/v1beta/files/f$uploads","state":"ACTIVE"}}""") }
                url.endsWith("/v1beta/interactions") && failInteractions != 0 -> GeminiTranscribe.Response(failInteractions, emptyMap(), """{"error":{"message":"denied"}}""")
                url.endsWith("/v1beta/interactions") -> { gate?.await(); GeminiTranscribe.Response(200, emptyMap(), if (speakers)
                    """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Hej hej. Hallå.","annotations":[
                    {"type":"word_info","text":"Hej","speaker":"spk_1","start_offset":"0.1s"},{"type":"word_info","text":"hej.","speaker":"spk_1","start_offset":"0.6s"},
                    {"type":"word_info","text":"Hallå.","speaker":"spk_2","start_offset":"2.2s"}]}]}]}""" else """{"steps":[{"type":"model_output","content":[{"type":"text","text":"Bara text."}]}]}""") }
                method == "DELETE" -> GeminiTranscribe.Response(200, emptyMap(), "{}")
                else -> error("Unexpected $method $url")
            }
        }
    }

    @Before fun setUp() { context.deleteDatabase("aegentica.db"); AudioNotes.dir(context).deleteRecursively(); shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO); TranscriptionService.clearFinished() }
    @After fun tearDown() { GeminiTranscribe.httpForTest = null; ConnectedAI.geminiKeyForTest = null; context.deleteDatabase("aegentica.db"); AudioNotes.dir(context).deleteRecursively() }

    @Test fun `record mode keeps audio parts, rolls over before the diarization limit and excludes pauses`() {
        val service = Robolectric.buildService(TranscriptionService::class.java).create()
        service.get().onStartCommand(Intent(context, TranscriptionService::class.java).putExtra(TranscriptionService.RECORD, true), 0, 1); idle()
        val state = TranscriptionService.state
        assertTrue(state.active && state.recording)
        val note = WorkspaceStore(context).use { it.get(state.record)!! }
        assertEquals(AudioNotes.SOURCE, note.source); assertTrue(Transcripts.isTranscript(note))
        File(AudioNotes.dir(context), "${note.id}-1.aac").writeBytes(ByteArray(100))
        idle(AudioNotes.PART_MS + 10)
        assertEquals(2, TranscriptionService.state.segments)
        File(AudioNotes.dir(context), "${note.id}-2.aac").writeBytes(ByteArray(100))
        idle(60_000)
        service.get().onStartCommand(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.PAUSE), 0, 2); idle(10 * 60_000)
        service.get().onStartCommand(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.RESUME), 0, 3); idle(30_000)
        service.get().onStartCommand(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.STOP), 0, 4); idle()
        assertFalse(TranscriptionService.state.active)
        val parts = WorkspaceStore(context).use { AudioNotes.parts(it, note.id) }
        assertEquals(2, parts.size)
        assertEquals(AudioNotes.PART_MS.toDouble(), parts[0].ms.toDouble(), 1000.0)
        assertEquals(90_000.0, parts[1].ms.toDouble(), 1000.0)
        // Permanent deletion removes the audio too.
        WorkspaceStore(context).use { store -> store.save(store.get(note.id)!!.copy(deleted = true)); store.purge(note.id); assertTrue(AudioNotes.parts(store, note.id).isEmpty()) }
        assertFalse(File(AudioNotes.dir(context), parts[0].file).exists())
        service.destroy()
    }

    @Test fun `a recording that captured nothing leaves no empty note`() {
        val service = Robolectric.buildService(TranscriptionService::class.java).create()
        service.get().onStartCommand(Intent(context, TranscriptionService::class.java).putExtra(TranscriptionService.RECORD, true), 0, 1); idle()
        service.get().onStartCommand(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.STOP), 0, 2); idle()
        assertEquals("", TranscriptionService.state.record)
        WorkspaceStore(context).use { assertTrue(it.list().isEmpty()) }
        service.destroy()
    }

    @Test fun `gemini transcription uploads, keeps nothing on Google and labels speakers with part offsets`() {
        val fake = FakeGemini(); GeminiTranscribe.httpForTest = fake
        val a = File(AudioNotes.dir(context), "a.m4a").apply { writeBytes(ByteArray(10)) }
        val b = File(AudioNotes.dir(context), "b.m4a").apply { writeBytes(ByteArray(10)) }
        val text = GeminiTranscribe.transcribe("k", listOf(a to AudioNotes.Part("a.m4a", 60_000), b to AudioNotes.Part("b.m4a", 1000)), { false }) {}
        assertEquals("[00:00] Speaker 1: Hej hej.\n[00:02] Speaker 2: Hallå.\n[01:00] Speaker 1: Hej hej.\n[01:02] Speaker 2: Hallå.", text)
        val request = JSONObject(fake.calls.first { it.second.endsWith("/interactions") }.third)
        assertEquals(GeminiTranscribe.MODEL, request.getString("model")); assertFalse(request.getBoolean("store"))
        val mode = request.getJSONObject("generation_config").getJSONObject("transcription_config").getJSONObject("mode")
        assertEquals("speaker", mode.getString("diarization_mode")); assertEquals("word", mode.getJSONArray("timestamp_granularities").getString(0))
        assertEquals("audio/aac", request.getJSONArray("input").getJSONObject(0).getString("mime_type"))
        assertEquals(listOf("files/f1", "files/f2"), fake.calls.filter { it.first == "DELETE" }.map { it.second.substringAfter("v1beta/") })

        GeminiTranscribe.httpForTest = FakeGemini(speakers = false)
        assertEquals("[00:00] Bara text.", GeminiTranscribe.transcribe("k", listOf(a to AudioNotes.Part("a.m4a", 40 * 60_000)), { false }) {})
        val denied = FakeGemini(failInteractions = 403); GeminiTranscribe.httpForTest = denied
        val error = assertThrows(IllegalStateException::class.java) { GeminiTranscribe.transcribe("k", listOf(a to AudioNotes.Part("a.m4a", 1000)), { false }) {} }
        assertTrue(error.message!!.contains("rejected the key")); assertTrue(denied.calls.any { it.first == "DELETE" })
        assertThrows(GeminiTranscribe.Cancelled::class.java) { GeminiTranscribe.transcribe("k", listOf(a to AudioNotes.Part("a.m4a", 1000)), { true }) {} }
    }

    @Test fun `recording note transcribes into its empty text and speakers are renamed only in the working text`() {
        val id = WorkspaceStore(context).use { store ->
            val note = store.save(Record(title = "Recording · test", source = AudioNotes.SOURCE))
            File(AudioNotes.dir(context), "${note.id}-1.aac").writeBytes(ByteArray(10))
            AudioNotes.add(store, note.id, AudioNotes.Part("${note.id}-1.aac", 5000)); note.id
        }
        GeminiTranscribe.httpForTest = FakeGemini(); ConnectedAI.geminiKeyForTest = "k"
        val activity = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.intent(context, "library", id)).setup()
        views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().first { it.tag == "audio-transcribe" }.performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        var waited = 0
        while (WorkspaceStore(context).use { it.get(id)!!.body }.isBlank() && waited < 100) { Thread.sleep(50); idle(); waited++ }
        idle()
        val saved = WorkspaceStore(context).use { it.get(id)!! }
        assertEquals(saved.body, saved.original); assertTrue(saved.original.startsWith("[00:00] Speaker 1: Hej hej."))
        assertEquals(listOf("Speaker 1", "Speaker 2"), Speakers.labels(saved.body))
        val renamed = Speakers.rename(saved.body, mapOf("Speaker 1" to "Riki", "Speaker 2" to "Lina"))
        assertEquals("[00:00] Riki: Hej hej.\n[00:02] Lina: Hallå.", renamed)
        assertEquals("Speaker 1: text mentions Speaker 2: inline", Speakers.rename("Speaker 1: text mentions Speaker 2: inline", mapOf("Speaker 2" to "X")))
        activity.pause().stop().destroy()
    }

    @Test fun `leaving the note cancels gemini transcription and drops the late result`() {
        val id = WorkspaceStore(context).use { store ->
            val note = store.save(Record(title = "Recording · test", source = AudioNotes.SOURCE))
            File(AudioNotes.dir(context), "${note.id}-1.aac").writeBytes(ByteArray(10))
            AudioNotes.add(store, note.id, AudioNotes.Part("${note.id}-1.aac", 5000)); note.id
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val fake = FakeGemini(gate = gate); GeminiTranscribe.httpForTest = fake; ConnectedAI.geminiKeyForTest = "k"
        val activity = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.intent(context, "library", id)).setup()
        views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().first { it.tag == "audio-transcribe" }.performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        var waited = 0
        while (fake.calls.none { it.second.endsWith("/interactions") } && waited < 100) { Thread.sleep(20); waited++ }
        assertTrue("transcription should be waiting on Gemini", fake.calls.any { it.second.endsWith("/interactions") })
        activity.pause()
        gate.countDown()
        waited = 0
        while (fake.calls.none { it.first == "DELETE" } && waited < 100) { Thread.sleep(20); waited++ }
        Thread.sleep(100); idle()
        assertTrue(WorkspaceStore(context).use { it.get(id)!! }.let { it.body.isBlank() && it.original.isBlank() })
        assertEquals(1, WorkspaceStore(context).use { it.list().size })
        assertTrue(fake.calls.any { it.first == "DELETE" })
        activity.stop().destroy()
    }

    @Test fun `audio cut off by a stopped app is listed again and nothing else is touched`() {
        val (id, other) = WorkspaceStore(context).use { store ->
            val note = store.save(Record(title = "Recording · crash", source = AudioNotes.SOURCE))
            File(AudioNotes.dir(context), "${note.id}-1.aac").writeBytes(ByteArray(8000))
            File(AudioNotes.dir(context), "${note.id}-2.aac").writeBytes(ByteArray(0))
            File(AudioNotes.dir(context), "missing-note-1.aac").writeBytes(ByteArray(10))
            note.id to File(AudioNotes.dir(context), "missing-note-1.aac")
        }
        WorkspaceStore(context).use { store ->
            assertEquals(1, AudioNotes.recover(context, store))
            val part = AudioNotes.parts(store, id).single()
            assertEquals("$id-1.aac", part.file); assertEquals(2000L, part.ms); assertEquals(AudioNotes.MIME, part.mime)
            assertFalse(File(AudioNotes.dir(context), "$id-2.aac").exists()); assertTrue(other.exists())
            assertEquals(0, AudioNotes.recover(context, store))
        }
    }

    @Test fun `shared audio becomes a private recording note and unsupported formats leave nothing behind`() {
        val uri = Uri.parse("content://recorder.example/audio/memo.m4a")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(2048) { 1 }))
        val id = AudioShareActivity.import(context, uri, "audio/mp4")
        WorkspaceStore(context).use { store ->
            val note = store.get(id)!!
            assertTrue(note.title.contains("memo.m4a")); assertTrue(Transcripts.isTranscript(note))
            val part = AudioNotes.parts(store, id).single()
            assertEquals("audio/m4a", part.mime); assertEquals(2048L, AudioNotes.file(context, part).length())
        }
        val amr = Uri.parse("content://recorder.example/audio/call.amr")
        shadowOf(context.contentResolver).registerInputStream(amr, ByteArrayInputStream(ByteArray(10)))
        assertThrows(IllegalStateException::class.java) { AudioShareActivity.import(context, amr, "audio/amr") }
        WorkspaceStore(context).use { assertEquals(1, it.list().size) }
        // An untrusted name cannot steer the private file outside the audio folder.
        val tricky = Uri.parse("content://recorder.example/audio/memo.%2E%2E%2F%2E%2E%2Fescape")
        shadowOf(context.contentResolver).registerInputStream(tricky, ByteArrayInputStream(ByteArray(10) { 1 }))
        val safe = AudioShareActivity.import(context, tricky, "audio/mp4")
        WorkspaceStore(context).use { store ->
            val file = AudioNotes.file(context, AudioNotes.parts(store, safe).single())
            assertEquals("$safe-1.m4a", file.name); assertEquals(AudioNotes.dir(context).canonicalPath, file.canonicalFile.parent)
        }
    }
}
