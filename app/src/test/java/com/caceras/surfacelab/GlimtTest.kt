package com.caceras.surfacelab

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.net.Socket
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class GlimtTest {

    private class FakePhone : GlimtBridge.Phone {
        val calls = mutableListOf<String>()
        var spoken: ((String?) -> Unit)? = null
        var heard: ((String, Boolean) -> Unit)? = null
        var signed: ((String?, String?) -> Unit)? = null
        override fun speak(text: String, language: String, done: (String?) -> Unit) { calls += "speak:$language:$text"; spoken = done }
        override fun stopSpeaking() { calls += "stop" }
        override fun listen(words: (String, Boolean) -> Unit, done: (String?) -> Unit) { calls += "listen"; heard = words }
        override fun stopListening() { calls += "listen-stop" }
        override fun signIn(challenge: String, done: (String?, String?) -> Unit) { calls += "sign-in:$challenge"; signed = done }
        override fun openOutside(url: String) { calls += "open:$url" }
        override fun menu() { calls += "menu" }
    }

    private fun bridge(): Triple<GlimtBridge, FakePhone, MutableList<JSONObject>> {
        val sent = mutableListOf<JSONObject>()
        val phone = FakePhone()
        return Triple(GlimtBridge({ sent += JSONObject(it) }, phone), phone, sent)
    }

    @Test fun `shared text waits for Glimt's hello, then speech, dictation and sign-in round-trip`() {
        val (bridge, phone, sent) = bridge()
        bridge.context("share", text = "Look at https://nordsko.se/fjall")
        assertTrue("nothing is sent before Glimt listens", sent.isEmpty())
        bridge.receive("""{"t":"hello","v":1}""")
        assertEquals("ready", sent[0].getString("t"))
        assertEquals("share", sent[1].getString("kind"))

        bridge.receive("""{"t":"speak","id":4,"text":"Hej då.","lang":"sv"}""")
        assertEquals("speak:sv:Hej då.", phone.calls.last())
        phone.spoken!!(null)
        assertEquals("end", sent.last().getString("state"))
        assertEquals(4, sent.last().getInt("id"))

        bridge.receive("""{"t":"listen","id":5}""")
        phone.heard!!("hello there", false)
        assertEquals("hello there", sent.last().getString("text"))
        assertFalse(sent.last().getBoolean("final"))

        val challenge = "a".repeat(43)
        bridge.receive("""{"t":"sign-in","challenge":"$challenge"}""")
        phone.signed!!("code-123", null)
        assertEquals("code-123", sent.last().getString("code"))
    }

    @Test fun `malformed and unsafe requests do nothing`() {
        val (bridge, phone, sent) = bridge()
        bridge.receive("not json")
        bridge.receive("""{"t":"run","code":"rm -rf"}""")
        bridge.receive("""{"t":"speak","text":"no id"}""")
        bridge.receive("""{"t":"open","url":"intent://evil#Intent;end"}""")
        bridge.receive("""{"t":"open","url":"file:///data/data/x"}""")
        assertTrue(phone.calls.isEmpty())
        bridge.receive("""{"t":"sign-in","challenge":"short"}""")
        assertTrue(phone.calls.isEmpty())
        assertEquals("Sign-in could not start.", sent.last().getString("error"))
        bridge.receive("""{"t":"open","url":"https://openrouter.ai/settings"}""")
        assertEquals("open:https://openrouter.ai/settings", phone.calls.last())
    }

    @Test fun `a new page load must say hello again before it hears anything`() {
        val (bridge, _, sent) = bridge()
        bridge.receive("""{"t":"hello"}""")
        bridge.reset()
        bridge.context("selection", text = "Kept for the next page")
        assertEquals(1, sent.size)
        bridge.receive("""{"t":"hello"}""")
        assertEquals("Kept for the next page", sent.last().getString("text"))
    }

    @Test fun `sign-in listens on localhost for OpenRouter's redirect and takes its code`() {
        var result: Pair<String?, String?>? = null
        val signIn = LoopbackSignIn { code, error -> result = code to error }
        val url = signIn.start("b".repeat(43), "intent://open#Intent;scheme=aegentica;end")
        assertEquals("openrouter.ai", url.host)
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        val callback = Uri.parse(url.getQueryParameter("callback_url"))
        assertEquals("localhost", callback.host)
        Socket("127.0.0.1", callback.port).use { s ->
            s.getOutputStream().write("GET /callback?code=abc-123 HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val reply = s.getInputStream().bufferedReader().readText()
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertTrue(reply.contains("Back to Ægentica"))
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        assertEquals("abc-123" to null, result)
        assertEquals(null, LoopbackSignIn.codeFrom("/callback?state=x"))
    }

    @Test fun `the app opens Glimt, announces itself, keeps Glimt in the app and sends other links out`() {
        val controller = Robolectric.buildActivity(GlimtActivity::class.java,
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A note to ask about")).setup()
        val activity = controller.get()
        val web = activity.window.decorView.findViewWithTag<WebView>("glimt")
        assertEquals(GlimtActivity.URL, shadowOf(web).lastLoadedUrl)
        assertTrue(web.settings.userAgentString.endsWith(GlimtActivity.USER_AGENT))
        assertFalse(web.settings.allowFileAccess)
        assertTrue(GlimtActivity.isGlimt(Uri.parse("https://glimt-136-148-209-184.sslip.io/?url=x")))
        assertFalse(GlimtActivity.isGlimt(Uri.parse("http://glimt-136-148-209-184.sslip.io/")))
        assertFalse(GlimtActivity.isGlimt(Uri.parse("https://glimt-136-148-209-184.sslip.io.evil.se/")))
        // The share was consumed, so recreation does not hand it over twice.
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        controller.pause().stop().destroy()
    }
}
