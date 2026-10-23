package com.caceras.surfacelab

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
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
class ShellTest {

    private class FakePhone : ShellBridge.Phone {
        val calls = mutableListOf<String>()
        var spoken: ((String?) -> Unit)? = null
        var heard: ((String, Boolean) -> Unit)? = null
        var signed: ((String?, String?) -> Unit)? = null
        override fun speak(text: String, language: String, done: (String?) -> Unit) { calls += "speak:$language:$text"; spoken = done }
        override fun pauseSpeaking() { calls += "pause" }
        override fun resumeSpeaking() { calls += "resume" }
        override fun stopSpeaking() { calls += "stop" }
        override fun listen(words: (String, Boolean) -> Unit, done: (String?) -> Unit) { calls += "listen"; heard = words }
        override fun stopListening() { calls += "listen-stop" }
        override fun signIn(challenge: String, done: (String?, String?) -> Unit) { calls += "sign-in:$challenge"; signed = done }
        override fun openOutside(url: String) { calls += "open:$url" }
        override fun chooseVoice() { calls += "voices" }
        override fun menu() { calls += "menu" }
    }

    /** A page asking for some of the web view's permissions, recording the answer. */
    private class Asking(private val from: Uri, private val wanted: Array<String>) : PermissionRequest() {
        var granted: List<String>? = null
        var denied = false
        override fun getOrigin() = from
        override fun getResources() = wanted
        override fun grant(resources: Array<String>) { granted = resources.toList() }
        override fun deny() { denied = true }
    }

    private fun bridge(): Triple<ShellBridge, FakePhone, MutableList<JSONObject>> {
        val sent = mutableListOf<JSONObject>()
        val phone = FakePhone()
        return Triple(ShellBridge({ sent += JSONObject(it) }, phone), phone, sent)
    }

    @Test fun `shared text waits for the page's hello, then speech, dictation and sign-in round-trip`() {
        val (bridge, phone, sent) = bridge()
        bridge.context("share", text = "Look at https://nordsko.se/fjall")
        assertTrue("nothing is sent before the page listens", sent.isEmpty())
        bridge.receive("""{"t":"hello","v":1}""")
        assertEquals("ready", sent[0].getString("t"))
        assertEquals("share", sent[1].getString("kind"))

        bridge.receive("""{"t":"speak","id":4,"text":"Hej då.","lang":"sv"}""")
        assertEquals("speak:sv:Hej då.", phone.calls.last())
        bridge.receive("""{"t":"pause"}""")
        bridge.receive("""{"t":"resume"}""")
        assertEquals(listOf("pause", "resume"), phone.calls.takeLast(2))
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

        bridge.receive("""{"t":"voices"}""")
        bridge.receive("""{"t":"menu"}""")
        assertEquals(listOf("voices", "menu"), phone.calls.takeLast(2))
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

    @Test fun `the app opens aegentica-se, announces itself, keeps the site in the app and consumes a share once`() {
        val controller = Robolectric.buildActivity(AegenticaActivity::class.java,
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A note to ask about")).setup()
        val activity = controller.get()
        val web = activity.window.decorView.findViewWithTag<WebView>("aegentica")
        assertEquals(AegenticaActivity.URL, shadowOf(web).lastLoadedUrl)
        assertTrue(web.settings.userAgentString.endsWith(ShellActivity.USER_AGENT))
        assertFalse(web.settings.allowFileAccess)
        assertTrue(AegenticaActivity.isAegentica(Uri.parse("https://aegentica.se/chat/abc")))
        assertTrue(AegenticaActivity.isAegentica(Uri.parse("https://www.aegentica.se/")))
        assertFalse(AegenticaActivity.isAegentica(Uri.parse("http://aegentica.se/")))
        assertFalse(AegenticaActivity.isAegentica(Uri.parse("https://aegentica.se.evil.se/")))
        assertFalse(AegenticaActivity.isAegentica(Uri.parse("https://glimt-136-148-209-184.sslip.io/")))
        assertFalse("other sites stay out", AegenticaActivity.isAegentica(Uri.parse("https://example.com/")))
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        controller.pause().stop().destroy()
    }

    @Test fun `aegentica-se may use the microphone after Android's prompt, Glimt and other sites never`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE
        val aegentica = Robolectric.buildActivity(AegenticaActivity::class.java).setup()
        val chrome = shadowOf(aegentica.get().window.decorView.findViewWithTag<WebView>("aegentica")).webChromeClient

        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val first = Asking(Uri.parse("https://aegentica.se/"), arrayOf(mic))
        chrome.onPermissionRequest(first)
        assertNull("asked Android first", first.granted)
        assertFalse(first.denied)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        aegentica.get().onRequestPermissionsResult(32, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(0))
        assertEquals(listOf(mic), first.granted)

        val again = Asking(Uri.parse("https://aegentica.se/"), arrayOf(mic))
        chrome.onPermissionRequest(again)
        assertEquals(listOf(mic), again.granted)
        val camera = Asking(Uri.parse("https://aegentica.se/"), arrayOf(mic, PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        chrome.onPermissionRequest(camera)
        assertTrue("the camera is never lent", camera.denied)
        val stranger = Asking(Uri.parse("https://example.com/"), arrayOf(mic))
        chrome.onPermissionRequest(stranger)
        assertTrue(stranger.denied)
        aegentica.pause().stop().destroy()

        val glimt = Robolectric.buildActivity(GlimtActivity::class.java).setup()
        val glimtChrome = shadowOf(glimt.get().window.decorView.findViewWithTag<WebView>("glimt")).webChromeClient
        val fromGlimt = Asking(Uri.parse("https://glimt-136-148-209-184.sslip.io/"), arrayOf(mic))
        glimtChrome.onPermissionRequest(fromGlimt)
        assertTrue("Glimt's dictation is native; its page gets no microphone", fromGlimt.denied)
        glimt.pause().stop().destroy()
    }

    @Test fun `Glimt still opens its own address and keeps only its origin in the app`() {
        val controller = Robolectric.buildActivity(GlimtActivity::class.java).setup()
        val web = controller.get().window.decorView.findViewWithTag<WebView>("glimt")
        assertEquals(GlimtActivity.URL, shadowOf(web).lastLoadedUrl)
        assertTrue(GlimtActivity.isGlimt(Uri.parse("https://glimt-136-148-209-184.sslip.io/?url=x")))
        assertFalse(GlimtActivity.isGlimt(Uri.parse("http://glimt-136-148-209-184.sslip.io/")))
        assertFalse(GlimtActivity.isGlimt(Uri.parse("https://glimt-136-148-209-184.sslip.io.evil.se/")))
        assertFalse(GlimtActivity.isGlimt(Uri.parse("https://aegentica.se/")))
        controller.pause().stop().destroy()
    }

    @Test fun `a page's file picker returns one file, several, or none when cancelled`() {
        val one = Uri.parse("content://media/1")
        val two = Uri.parse("content://media/2")
        assertArrayEquals(arrayOf(one), ShellActivity.chosenFiles(android.app.Activity.RESULT_OK, Intent().setData(one)))
        val several = Intent().apply {
            clipData = ClipData.newRawUri("", one).apply { addItem(ClipData.Item(two)) }
        }
        assertArrayEquals(arrayOf(one, two), ShellActivity.chosenFiles(android.app.Activity.RESULT_OK, several))
        assertNull(ShellActivity.chosenFiles(android.app.Activity.RESULT_CANCELED, Intent().setData(one)))
    }
}
