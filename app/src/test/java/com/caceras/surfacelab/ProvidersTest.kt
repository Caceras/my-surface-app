package com.caceras.surfacelab

import android.os.Looper
import android.view.View
import android.widget.EditText
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

/** OpenRouter, Gemini and local servers behind one streaming client; keys per provider. */
@RunWith(AndroidJUnit4::class)
class ProvidersTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("connected-ai", 0)
    private fun views(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    /** Answers every request with one scripted response and keeps what was sent. */
    class FakeServer(private val code: Int = 200, private val type: String = "text/event-stream", private val body: String) : ConnectedAI.Http {
        val sent = mutableListOf<Pair<Map<String, String>, JSONObject>>()
        var cancelled = false
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, onCall: (ConnectedAI.Call) -> Unit): ConnectedAI.Response {
            onCall(ConnectedAI.Call { cancelled = true })
            sent += headers to JSONObject(body.toString(Charsets.UTF_8))
            return ConnectedAI.Response(code, type, ByteArrayInputStream(this.body.toByteArray())) {}
        }
    }

    private fun connect(endpoint: String, model: String = "m", vision: Boolean = true, voice: Boolean = false) =
        prefs().edit().clear().putString("endpoint", endpoint).putString("model", model).putBoolean("keyless", true)
            .putBoolean("vision", vision).putBoolean("voice", voice).putBoolean("enabled", true).commit()

    private val stream = """
        : OPENROUTER PROCESSING

        data: {"id":"gen-1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hej"},"finish_reason":null}]}

        : OPENROUTER PROCESSING
        data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":" där."},"finish_reason":null}]}
        data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}
        data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"cost":0.0001}}
        data: [DONE]
    """.trimIndent()

    @Before fun setUp() { prefs().edit().clear().commit() }
    @After fun tearDown() { ConnectedAI.httpForTest = null; prefs().edit().clear().commit() }

    @Test fun `answers stream in fragments and keep-alive comments are skipped`() {
        connect(ConnectedAI.OPENROUTER_ENDPOINT)
        val server = FakeServer(body = stream); ConnectedAI.httpForTest = server
        val seen = mutableListOf<String>()
        val text = ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "Hej?"))) { seen += it }
        assertEquals("Hej där.", text)
        assertEquals(listOf("Hej", "Hej där."), seen)
        val (headers, body) = server.sent.single()
        assertFalse("a keyless local setup sends no Authorization header", headers.containsKey("Authorization"))
        assertTrue(body.getBoolean("stream"))
    }

    @Test fun `errors inside a successful stream and plain HTTP errors become clear messages`() {
        connect(ConnectedAI.OPENROUTER_ENDPOINT)
        ConnectedAI.httpForTest = FakeServer(body = """data: {"error":{"code":429,"message":"Rate limit exceeded"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""")
        val limited = assertThrows(IllegalStateException::class.java) { ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "x"))) }
        assertTrue(limited.message!!.contains("rate limiting")); assertTrue(limited.message!!.contains("No automatic retry"))
        ConnectedAI.httpForTest = FakeServer(402, "application/json", """{"error":{"code":402,"message":"Insufficient credits","metadata":{"limit_source":"openrouter_credits"}}}""")
        assertTrue(assertThrows(IllegalStateException::class.java) { ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "x"))) }.message!!.contains("out of credits"))
        ConnectedAI.httpForTest = FakeServer(403, "application/json", """{"error":{"code":403,"message":"Key limit exceeded","metadata":{"limit_source":"openrouter_key_limit"}}}""")
        assertTrue(assertThrows(IllegalStateException::class.java) { ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "x"))) }.message!!.contains("spending limit"))
        // A 200 JSON body carrying only an error is still a failure.
        ConnectedAI.httpForTest = FakeServer(200, "application/json", """{"error":{"code":502,"message":"Provider returned error"}}""")
        assertTrue(assertThrows(IllegalStateException::class.java) { ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "x"))) }.message!!.contains("unavailable"))
        // Servers that ignore stream=true answer with one JSON object.
        ConnectedAI.httpForTest = FakeServer(200, "application/json", """{"choices":[{"message":{"role":"assistant","content":"Klart."}}]}""")
        assertEquals("Klart.", ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "x"))))
    }

    @Test fun `requests carry each provider's own options and images only go to models that see them`() {
        val image = ByteArray(8) { it.toByte() }
        val openRouter = ConnectedAI.body("google/gemini-3.8-flash", listOf(ConnectedAI.Message("user", "Vad ser du?", image)), ConnectedAI.OPENROUTER_ENDPOINT)
        assertEquals("deny", openRouter.getJSONObject("provider").getString("data_collection"))
        assertEquals("low", openRouter.getJSONObject("reasoning").getString("effort"))
        assertTrue(openRouter.has("max_tokens")); assertFalse(openRouter.has("max_completion_tokens"))
        val parts = openRouter.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        val local = ConnectedAI.body("gemma4:12b", listOf(ConnectedAI.Message("user", "x")), "http://mac.tail.ts.net:11434/v1/chat/completions", local = true)
        assertTrue(local.has("max_tokens")); assertFalse(local.has("provider")); assertFalse(local.has("reasoning_effort"))

        connect(ConnectedAI.OPENROUTER_ENDPOINT, vision = false)
        val server = FakeServer(body = stream); ConnectedAI.httpForTest = server
        ConnectedAI.complete(context, listOf(ConnectedAI.Message("user", "Vad ser du?", image)))
        assertEquals("Vad ser du?", server.sent.single().second.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `a saved Gemini key survives switching chat to OpenRouter and local servers stay inside the tailnet`() {
        // Legacy single-key install: Gemini was the active provider.
        prefs().edit().putString("endpoint", ConnectedAI.GEMINI_ENDPOINT).putString("secret", "iv:data").commit()
        assertTrue(ConnectedAI.geminiConfigured(context))
        // Gemini key saved under its host, chat now on OpenRouter with another key.
        prefs().edit().putString("key:generativelanguage.googleapis.com", "iv:gemini").putString("endpoint", ConnectedAI.OPENROUTER_ENDPOINT).putString("secret", "iv:openrouter").putString("key:openrouter.ai", "iv:openrouter").commit()
        assertTrue(ConnectedAI.geminiConfigured(context)); assertTrue(ConnectedAI.configured(context))
        assertEquals("OpenRouter · ", ConnectedAI.label(context))

        assertEquals("https://mac.tail.ts.net/v1/chat/completions", ConnectedAI.localEndpoint("https://mac.tail.ts.net/"))
        assertEquals("http://mac.tail.ts.net:1234/v1/chat/completions", ConnectedAI.localEndpoint("http://mac.tail.ts.net:1234/v1"))
        ConnectedAI.validateEndpoint("http://mac.tail.ts.net:11434/v1/chat/completions")
        assertThrows(IllegalArgumentException::class.java) { ConnectedAI.validateEndpoint("http://192.168.1.20:11434/v1/chat/completions") }
        assertThrows(IllegalArgumentException::class.java) { ConnectedAI.validateEndpoint("http://evil.ts.net.example.com/v1/chat/completions") }
    }

    @Test fun `voice uses the connected model only after its own opt-in and streams to the screen`() {
        connect(ConnectedAI.OPENROUTER_ENDPOINT, voice = false)
        assertNotSame(ConnectedAI.brain, ConnectedAI.assistantBrain(context))
        connect(ConnectedAI.OPENROUTER_ENDPOINT, voice = true)
        assertSame(ConnectedAI.brain, ConnectedAI.assistantBrain(context))
        ConnectedAI.httpForTest = FakeServer(body = stream)
        val partials = mutableListOf<String>(); var result: BrainResult? = null
        ConnectedAI.brain.run(context, Task.ASK, "", "Hej?", onPartial = { partials += it }) { result = it }
        var waited = 0
        while (result == null && waited < 100) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle(); waited++ }
        assertEquals("Hej där.", result!!.text); assertTrue(result!!.ok)
        assertEquals("Hej där.", partials.last())
    }

    @Test fun `the model list puts recommended models first and leaves out models that cannot answer in text`() {
        val raw = """{"data":[
            {"id":"recursive/flint-v1.0:free","name":"Recursive: Flint (free)","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"},"supported_parameters":[]},
            {"id":"example/painter","name":"Painter","architecture":{"input_modalities":["text"],"output_modalities":["image"]},"pricing":{"prompt":"0.000001","completion":"0.000001"},"supported_parameters":[]},
            {"id":"anthropic/claude-haiku-5.5","name":"Anthropic: Claude Haiku 5.5","architecture":{"input_modalities":["text","image","file"],"output_modalities":["text"]},"pricing":{"prompt":"0.0000001","completion":"0.0000005"},"supported_parameters":["max_tokens","tools"]},
            {"id":"google/gemini-3.8-flash","name":"Google: Gemini 3.8 Flash","architecture":{"input_modalities":["text","image","video","file","audio"],"output_modalities":["text"]},"pricing":{"prompt":"0.000000825","completion":"0.000004125"},"supported_parameters":["reasoning","tools"]}]}"""
        val models = ConnectedAI.parseModels(raw)
        assertEquals(listOf("google/gemini-3.8-flash", "anthropic/claude-haiku-5.5", "recursive/flint-v1.0:free"), models.map { it.id })
        assertTrue(models[0].vision && models[0].audio && models[0].tools)
        assertEquals("sees images · hears audio · tools · $0.83 in / $4.13 out per M tokens", models[0].summary)
        assertEquals("free", models[2].summary)
    }

    @Test fun `the settings presets fill OpenRouter and only OpenRouter offers the model list`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ConnectedAI.settings(activity)
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        val fields = views(decor).filterIsInstance<EditText>()
        val choose = views(decor).first { it.tag == "connected-choose-model" }
        assertEquals(View.GONE, choose.visibility)
        views(decor).filterIsInstance<TextView>().first { it.tag == "connected-openrouter" }.performClick()
        assertEquals(ConnectedAI.OPENROUTER_ENDPOINT, fields[0].text.toString())
        assertEquals(ConnectedAI.OPENROUTER_MODEL, fields[1].text.toString())
        assertEquals(View.VISIBLE, choose.visibility)
        views(decor).filterIsInstance<TextView>().first { it.tag == "connected-gemini" }.performClick()
        assertEquals(ConnectedAI.GEMINI_ENDPOINT, fields[0].text.toString())
        assertEquals(View.GONE, choose.visibility)
    }
}
