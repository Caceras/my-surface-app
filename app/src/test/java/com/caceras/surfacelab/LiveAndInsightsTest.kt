package com.caceras.surfacelab

import android.Manifest
import android.content.Context
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
import java.util.Base64

/** Gemini Live protocol/session over a fake socket, insights review and the verbatim diff. */
@RunWith(AndroidJUnit4::class)
class LiveAndInsightsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    class FakeSocket(val listener: LiveTransportListener) : LiveTransport {
        val sent = mutableListOf<String>(); var closed = false
        override fun send(text: String): Boolean { sent += text; return !closed }
        override fun close() { closed = true }
    }
    class FakeAudio : LiveAudio {
        var chunk: ((ByteArray) -> Unit)? = null; val played = mutableListOf<ByteArray>(); var flushed = 0; var stopped = false
        override fun start(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit): Boolean { chunk = onChunk; return true }
        override fun play(pcm: ByteArray) { played += pcm }
        override fun flush() { flushed++ }
        override fun stop() { stopped = true }
    }
    class Recorder : LiveSession.Listener {
        val states = mutableListOf<Pair<LiveSession.State, String>>(); var lines = listOf<LiveLine>(); var current = ""
        override fun state(state: LiveSession.State, detail: String) { states += state to detail }
        override fun transcript(lines: List<LiveLine>, current: String) { this.lines = lines; this.current = current }
    }

    private val sockets = mutableListOf<FakeSocket>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun server(json: String) { sockets.last().listener.message(json); idle() }
    private fun session(audio: FakeAudio, recorder: Recorder) = LiveSession(context, "test-key", audio, recorder) { url, listener ->
        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com/ws/") && url.endsWith("?key=test-key"))
        FakeSocket(listener).also { sockets += it }
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    @Before fun setUp() { context.deleteDatabase("aegentica.db") }
    @After fun tearDown() { Brains.useForTest(null); Live.transportForTest = null; Live.audioForTest = null; Live.keyForTest = null; context.deleteDatabase("aegentica.db") }

    @Test fun `live session sets up tools, streams audio only after setup and answers note searches read-only`() {
        WorkspaceStore(context).use { store ->
            Transcripts.append(store, null, 0, 1, 5_000, "vi bestämde att lansera onboarding på fredag", "sv-SE")
            store.save(Record(title = "Inköp", body = "mjölk och bröd"))
        }
        val audio = FakeAudio(); val recorder = Recorder()
        val live = session(audio, recorder); live.start()
        sockets.last().listener.opened(); idle()
        val setup = JSONObject(sockets.last().sent.single()).getJSONObject("setup")
        assertEquals("models/${LiveProtocol.MODEL}", setup.getString("model"))
        assertEquals("AUDIO", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertTrue(setup.has("inputAudioTranscription") && setup.has("outputAudioTranscription") && setup.has("sessionResumption"))
        val tools = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(listOf("search_notes", "read_note", "list_tasks"), (0 until tools.length()).map { tools.getJSONObject(it).getString("name") })
        assertNull("No microphone before setup completes", audio.chunk)

        server("""{"setupComplete":{}}""")
        assertEquals(LiveSession.State.LISTENING, recorder.states.last().first)
        audio.chunk!!(byteArrayOf(1, 2, 3, 4))
        val chunk = JSONObject(sockets.last().sent.last()).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", chunk.getString("mimeType")); assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(chunk.getString("data")))

        server("""{"toolCall":{"functionCalls":[{"id":"c1","name":"search_notes","args":{"query":"onboarding fredag"}}]}}""")
        val response = JSONObject(sockets.last().sent.last()).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", response.getString("id"))
        val result = response.getJSONObject("response").toString()
        assertTrue(result.contains("lansera onboarding")); assertFalse(result.contains("mjölk"))
        assertTrue(live.consulted.single().startsWith("Transcript"))
        server("""{"toolCall":{"functionCalls":[{"id":"c2","name":"send_message","args":{}}]}}""")
        assertTrue(sockets.last().sent.last().contains("Unknown function"))
        WorkspaceStore(context).use { assertEquals(2, it.list().size) }
        live.end()
    }

    @Test fun `spoken replies play, barge-in flushes, transcripts become a saved verbatim note`() {
        val audio = FakeAudio(); val recorder = Recorder()
        val live = session(audio, recorder); live.start(); sockets.last().listener.opened(); idle()
        server("""{"setupComplete":{}}""")
        server("""{"serverContent":{"inputTranscription":{"text":"Vad sa jag om "}}}""")
        server("""{"serverContent":{"inputTranscription":{"text":"lanseringen?"}}}""")
        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(9, 9))
        server("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]},"outputTranscription":{"text":"Du sa fredag."}}}""")
        assertEquals(1, audio.played.size); assertEquals(LiveSession.State.SPEAKING, recorder.states.last().first)
        server("""{"serverContent":{"interrupted":true}}""")
        assertEquals(1, audio.flushed)
        assertEquals(listOf(LiveLine(true, "Vad sa jag om lanseringen?"), LiveLine(false, "Du sa fredag.")), recorder.lines)
        live.end()
        assertTrue(audio.stopped); assertTrue(sockets.last().closed)
        assertTrue(sockets.last().sent.any { it.contains("audioStreamEnd") })
        val note = WorkspaceStore(context).use { live.save(it)!! }
        assertEquals("You: Vad sa jag om lanseringen?\nGemini: Du sa fredag.", note.original)
        assertTrue(note.source.startsWith("Gemini Live"))
    }

    @Test fun `go-away resumes with the latest handle and a failure without one ends cleanly`() {
        val audio = FakeAudio(); val recorder = Recorder()
        val live = session(audio, recorder); live.start(); sockets.last().listener.opened(); idle()
        server("""{"setupComplete":{}}""")
        server("""{"sessionResumptionUpdate":{"newHandle":"h-42","resumable":true}}""")
        server("""{"goAway":{"timeLeft":"30s"}}""")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(2, sockets.size); assertTrue(sockets[0].closed)
        sockets.last().listener.opened(); idle()
        assertEquals("h-42", JSONObject(sockets.last().sent.single()).getJSONObject("setup").getJSONObject("sessionResumption").getString("handle"))
        // A stale socket's late messages are ignored.
        sockets[0].listener.message("""{"serverContent":{"outputTranscription":{"text":"stale"}}}"""); idle()
        assertFalse(recorder.current.contains("stale"))

        val other = Recorder(); val first = session(FakeAudio(), other); first.start()
        sockets.last().listener.failed("HTTP 403 Forbidden"); idle()
        assertEquals(LiveSession.State.ENDED, other.states.last().first)
        assertTrue(other.states.last().second.contains("rejected the key"))
        live.end()
    }

    @Test fun `live screen runs a conversation, ends on leaving and saves it`() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        context.getSharedPreferences("surfacelab", 0).edit().putBoolean("live_consent", true).commit()
        val audio = FakeAudio()
        Live.keyForTest = "test-key"; Live.audioForTest = audio
        Live.transportForTest = { _, listener -> FakeSocket(listener).also { sockets += it } }
        val screen = Robolectric.buildActivity(LiveActivity::class.java, LiveActivity.intent(context, start = true)).setup()
        sockets.last().listener.opened(); idle(); server("""{"setupComplete":{}}""")
        server("""{"serverContent":{"inputTranscription":{"text":"Hej"},"outputTranscription":{"text":"Hej Riki."},"turnComplete":true}}""")
        assertTrue(views(screen.get().window.decorView).filterIsInstance<TextView>().any { it.tag == "live-text" && it.text.contains("Hej Riki.") })
        screen.pause()
        assertTrue(audio.stopped)
        assertEquals("You: Hej\nGemini: Hej Riki.", WorkspaceStore(context).use { it.list().single().original })
        screen.stop().destroy()
    }

    private class JsonBrain(private val reply: String) : SurfaceBrain {
        override val tasks = listOf(Task.ASK)
        override fun status(context: Context, onStatus: (BrainStatus) -> Unit) = onStatus(BrainStatus("Test", true))
        override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) = status(context, onStatus)
        override fun run(context: Context, task: Task, input: String, instruction: String, onPartial: (String) -> Unit, onResult: (BrainResult) -> Unit) {
            assertTrue(instruction.contains("JSON")); onResult(BrainResult(reply, true))
        }
    }

    @Test fun `insights are reviewed, applied as linked records with provenance and undone exactly`() {
        val reply = "Här är JSON:\n```json\n{\"title\":\"Onboarding med tre exempel\",\"summary\":[\"Visa tre exempel\"],\"tasks\":[\"Skicka utkast till Lina\"],\"people\":[\"Lina\",\"Omar\"],\"projects\":[\"Ægentica\"]}\n```"
        assertEquals("Onboarding med tre exempel", Insights.parse(reply)!!.title)
        assertNull(Insights.parse("no json here"))
        val (note, lina) = WorkspaceStore(context).use { store ->
            Transcripts.append(store, null, 0, 1, 1_000, "lina ska få utkastet och vi visar tre exempel i ægentica", "sv-SE") to store.save(Record(kind = "person", title = "Lina"))
        }
        Brains.useForTest(JsonBrain(reply))
        val activity = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.intent(context, "library", note.id)).setup()
        views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().first { it.text == "Insights" }.performClick()
        idle()
        val review = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val items = (0 until review.listView.adapter.count).map { review.listView.adapter.getItem(it).toString() }
        assertTrue(items.contains("Title: Onboarding med tre exempel")); assertTrue(items.contains("Person: Lina (link existing)")); assertTrue(items.contains("Person: Omar (new)"))
        review.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        WorkspaceStore(context).use { store ->
            val updated = store.get(note.id)!!
            assertEquals("Onboarding med tre exempel", updated.title); assertEquals(note.original, updated.original); assertEquals(note.body, updated.body)
            val links = store.linked(note.id)
            assertTrue(links.any { it.id == lina.id }); assertTrue(links.any { it.kind == "person" && it.title == "Omar" && it.source.startsWith("AI insight") })
            assertTrue(links.any { it.kind == "task" && it.title == "Skicka utkast till Lina" })
            assertEquals(1, store.list(kind = "person").count { it.title == "Lina" })
        }
        val confirm = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        WorkspaceStore(context).use { store ->
            assertEquals(note.title, store.get(note.id)!!.title)
            assertEquals(listOf("Lina"), store.list().filter { it.kind != "note" }.map { it.title })
            assertTrue(store.linked(note.id).isEmpty())
        }
        activity.pause().stop().destroy()
    }

    @Test fun `verbatim diff marks removed and added words and refuses oversized input`() {
        val pieces = TextDiff.words("eh vi ses på fredag", "Vi ses på fredag.")!!
        assertEquals(listOf(TextDiff.Kind.REMOVED, TextDiff.Kind.ADDED, TextDiff.Kind.SAME, TextDiff.Kind.REMOVED, TextDiff.Kind.ADDED), pieces.map { it.kind })
        assertEquals("eh vi", pieces[0].text)
        assertEquals("ses på", pieces[2].text)
        assertNull(TextDiff.words("ord ".repeat(TextDiff.MAX_WORDS + 1), "ord"))
    }
}
