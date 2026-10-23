package com.caceras.surfacelab

import org.json.JSONObject

/**
 * What a page in the app may ask of the phone, and what the phone tells it, over the message
 * channel [ShellActivity] hands to the page (Ægentica's own site, or Glimt). Messages are JSON
 * strings; anything unknown or malformed is ignored. The pages are the owner's own code from
 * their server, but every request is still checked: links leave only as http(s), text is
 * bounded, and no answer is ever run as a command.
 */
class ShellBridge(private val send: (String) -> Unit, private val phone: Phone) {

    /** The phone's side of each request, so the bridge can be tested without a phone. */
    interface Phone {
        fun speak(text: String, language: String, done: (error: String?) -> Unit)
        fun pauseSpeaking()
        fun resumeSpeaking()
        fun stopSpeaking()
        fun listen(words: (text: String, final: Boolean) -> Unit, done: (error: String?) -> Unit)
        fun stopListening()
        fun signIn(challenge: String, done: (code: String?, error: String?) -> Unit)
        fun openOutside(url: String)
        fun chooseVoice()
        fun menu()
    }

    private var connected = false
    private val waiting = ArrayDeque<String>()

    /** A new page load: the page says hello again before it hears anything. */
    fun reset() {
        connected = false
    }

    /** Hand the page what another app shared; kept until the page says hello. */
    fun context(kind: String, text: String? = null, url: String? = null, title: String? = null,
                app: String? = null, screenshot: String? = null) {
        val m = JSONObject().put("t", "context").put("kind", kind)
        text?.let { m.put("text", it.take(MAX_TEXT)) }
        url?.let { m.put("url", it) }
        title?.let { m.put("title", it.take(300)) }
        app?.let { m.put("app", it.take(100)) }
        screenshot?.let { m.put("screenshot", it) }
        if (connected) send(m.toString()) else waiting.addLast(m.toString())
    }

    fun receive(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (m.optString("t")) {
            "hello" -> {
                connected = true
                send(JSONObject().put("t", "ready").put("app", APP).toString())
                while (waiting.isNotEmpty()) send(waiting.removeFirst())
            }
            "speak" -> {
                val id = m.optInt("id", -1)
                val text = m.optString("text").take(MAX_TEXT)
                if (id < 0 || text.isBlank()) return
                phone.speak(text, m.optString("lang")) { error ->
                    send(JSONObject().put("t", "speech").put("id", id)
                        .put("state", if (error == null) "end" else "error")
                        .apply { error?.let { put("message", it) } }.toString())
                }
            }
            "pause" -> phone.pauseSpeaking()
            "resume" -> phone.resumeSpeaking()
            "stop" -> phone.stopSpeaking()
            "listen" -> {
                val id = m.optInt("id", -1)
                if (id < 0) return
                phone.listen({ text, final ->
                    send(JSONObject().put("t", "words").put("id", id).put("text", text).put("final", final).toString())
                }) { error ->
                    send(JSONObject().put("t", "listen-end").put("id", id)
                        .apply { error?.let { put("error", it) } }.toString())
                }
            }
            "listen-stop" -> phone.stopListening()
            "sign-in" -> {
                val challenge = m.optString("challenge")
                if (!CHALLENGE.matches(challenge)) {
                    send(JSONObject().put("t", "sign-in").put("error", "Sign-in could not start.").toString())
                    return
                }
                phone.signIn(challenge) { code, error ->
                    send(JSONObject().put("t", "sign-in")
                        .apply { if (code != null) put("code", code) else put("error", error ?: "Sign-in was cancelled.") }
                        .toString())
                }
            }
            "open" -> {
                val url = m.optString("url")
                if (url.startsWith("https://") || url.startsWith("http://")) phone.openOutside(url)
            }
            "voices" -> phone.chooseVoice()
            "menu" -> phone.menu()
        }
    }

    companion object {
        const val APP = "Ægentica AI"
        const val MAX_TEXT = 200_000
        /** A PKCE S256 challenge: 43 base64url characters. */
        private val CHALLENGE = Regex("[A-Za-z0-9_-]{43,128}")
    }
}
