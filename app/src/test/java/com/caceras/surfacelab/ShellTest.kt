package com.caceras.surfacelab

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
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

    @Test fun `a screen goes to the page with its screenshot, and the protocol stays version 1`() {
        val (bridge, _, sent) = bridge()
        val shot = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
        bridge.context("screen", text = "Hej Riki", title = "My screen in Messages", app = "Messages", screenshot = shot)
        bridge.context("screen", text = "Not a picture", screenshot = "javascript:alert(1)")
        bridge.context("screen", text = "Too big", screenshot = "data:image/jpeg;base64," + "A".repeat(ScreenContexts.MAX_DATA_URL))
        bridge.context("share", text = "No picture at all")
        bridge.receive("""{"t":"hello","v":1}""")
        assertEquals(ShellBridge.APP, sent[0].getString("app"))
        assertEquals("pages still find the app by the same user agent", "AegenticaShell/1", ShellActivity.USER_AGENT)
        val screen = sent[1]
        assertEquals("screen", screen.getString("kind"))
        assertEquals("Hej Riki", screen.getString("text"))
        assertEquals("My screen in Messages", screen.getString("title"))
        assertEquals("Messages", screen.getString("app"))
        assertEquals(shot, screen.getString("screenshot"))
        assertFalse("only a JPEG data URL goes along", sent[2].has("screenshot"))
        assertEquals("Not a picture", sent[2].getString("text"))
        assertFalse("an oversized one is left out", sent[3].has("screenshot"))
        assertFalse(sent[4].has("screenshot"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the screenshot travels as a JPEG of at most 1280 pixels and about 1,5 MB, smaller when busy`() {
        // A Pixel 10 Pro XL screen, portrait.
        val screen = Bitmap.createBitmap(1344, 2992, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val url = ScreenContexts.dataUrl(screen)!!
        assertTrue(url.startsWith("data:image/jpeg;base64,/9j/"))
        assertTrue(url.length <= ScreenContexts.MAX_DATA_URL)
        val picture = decode(url)
        assertEquals(1280, picture.height)
        assertEquals(574, picture.width)

        // Noise compresses badly: under a tight limit it is compressed harder, then made smaller.
        val random = java.util.Random(7)
        val busy = Bitmap.createBitmap(1280, 576, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }, 0, width, 0, 0, width, height)
        }
        val full = ScreenContexts.dataUrl(busy)!!
        val tight = ScreenContexts.dataUrl(busy, maxChars = full.length / 4)!!
        assertTrue(tight.length <= full.length / 4)
        assertTrue("still a picture", decode(tight).width in 1..1280)
        assertNull("nothing fits in a few bytes", ScreenContexts.dataUrl(busy, maxChars = 100))
    }

    private fun decode(url: String): Bitmap {
        val bytes = android.util.Base64.decode(url.substringAfter(","), android.util.Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test fun `the assistant's screen opens aegentica-se once, held in memory and never in the intent`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shot = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
        val open = ShellActivity.handOver(AegenticaActivity.intent(context),
            ShellActivity.Handed("screen", "Hej Riki", "My screen in Messages", "Messages", shot))
        assertTrue("the intent carries no data", open.extras?.isEmpty ?: true)
        val controller = Robolectric.buildActivity(AegenticaActivity::class.java, open).setup()
        val activity = controller.get()
        val queued = activity.bridge.queued().map { JSONObject(it) }
        assertEquals("kept until the page says hello", 1, queued.size)
        assertEquals("screen", queued[0].getString("kind"))
        assertEquals("Hej Riki", queued[0].getString("text"))
        assertEquals("My screen in Messages", queued[0].getString("title"))
        assertEquals(shot, queued[0].getString("screenshot"))
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)

        // Another app sending the same action, or the same start again, hands over nothing.
        controller.newIntent(Intent(ShellActivity.ACTION_CONTEXT).setClass(context, AegenticaActivity::class.java))
        assertEquals(1, activity.bridge.queued().size)

        // A hand-over the shell never picked up is dropped after a minute.
        ShellActivity.handOver(Intent(), ShellActivity.Handed("screen", "Old", null, null, null))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        controller.newIntent(Intent(ShellActivity.ACTION_CONTEXT).setClass(context, AegenticaActivity::class.java))
        assertEquals(1, activity.bridge.queued().size)
        assertNull(ShellActivity.take())
        controller.pause().stop().destroy()
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
        assertTrue("the bar opens here too", AegenticaActivity.isAegentica(Uri.parse("https://aegentica.se/bar")))
        assertFalse("other sites stay out", AegenticaActivity.isAegentica(Uri.parse("https://example.com/")))
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        controller.pause().stop().destroy()
    }

    @Test fun `aegentica-se may use the microphone after Android's prompt, the bar and other sites never`() {
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

        val bar = Robolectric.buildActivity(BarActivity::class.java).setup()
        val barChrome = shadowOf(bar.get().window.decorView.findViewWithTag<WebView>("bar")).webChromeClient
        val fromBar = Asking(Uri.parse("https://aegentica.se/bar"), arrayOf(mic))
        barChrome.onPermissionRequest(fromBar)
        assertTrue("the bar's dictation is native; its page gets no microphone", fromBar.denied)
        bar.pause().stop().destroy()
    }

    @Test fun `the bar opens aegentica-se-bar and keeps only the bar in its screen`() {
        val controller = Robolectric.buildActivity(BarActivity::class.java).setup()
        val web = controller.get().window.decorView.findViewWithTag<WebView>("bar")
        assertEquals(BarActivity.URL, shadowOf(web).lastLoadedUrl)
        assertTrue(web.settings.userAgentString.endsWith(ShellActivity.USER_AGENT))
        assertTrue(BarActivity.isBar(Uri.parse("https://aegentica.se/bar")))
        assertTrue(BarActivity.isBar(Uri.parse("https://aegentica.se/bar?url=https%3A%2F%2Fexample.com")))
        assertTrue(BarActivity.isBar(Uri.parse("https://www.aegentica.se/bar/assets/app.js")))
        assertFalse("another page of the site", BarActivity.isBar(Uri.parse("https://aegentica.se/")))
        assertFalse("only the bar's own path", BarActivity.isBar(Uri.parse("https://aegentica.se/barn")))
        assertFalse(BarActivity.isBar(Uri.parse("http://aegentica.se/bar")))
        assertFalse(BarActivity.isBar(Uri.parse("https://aegentica.se:8443/bar")))
        assertFalse(BarActivity.isBar(Uri.parse("https://aegentica.se.evil.se/bar")))
        assertFalse(BarActivity.isBar(Uri.parse("https://glimt-136-148-209-184.sslip.io/")))
        controller.pause().stop().destroy()
    }

    @Test fun `from the bar, aegentica-se's other pages open in its own screen and other sites in the browser`() {
        val controller = Robolectric.buildActivity(BarActivity::class.java).setup()
        val activity = controller.get()
        activity.openOutside("https://aegentica.se/chat/abc")
        val ours = shadowOf(activity).nextStartedActivity
        assertEquals(AegenticaActivity::class.java.name, ours.component?.className)
        assertEquals("https://aegentica.se/chat/abc", ours.getStringExtra(ShellActivity.EXTRA_URL))
        activity.openOutside("https://example.com/")
        val theirs = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, theirs.action)
        assertEquals("https://example.com/", theirs.dataString)
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
