package com.caceras.surfacelab

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The phone agent's own OpenRouter sign-in: OpenRouter's sign-in page in Chrome (PKCE, with the
 * loopback redirect [LoopbackSignIn] already uses for Glimt), then the code is exchanged for a key
 * that is saved encrypted beside Connected AI's other keys. Lives in the process, not in a screen,
 * because the assistant sheet closes when Chrome opens.
 */
object OpenRouterSignIn {
    const val EXCHANGE = "https://openrouter.ai/api/v1/auth/keys"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var signIn: LoopbackSignIn? = null
    @Volatile private var verifier: String? = null

    /** A PKCE verifier: 64 random base64url characters. */
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        Base64.encodeToString(ByteArray(48).also { random.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** S256: base64url of the verifier's SHA-256, without padding. */
    fun challenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun start(context: Context) {
        val app = context.applicationContext
        val code = newVerifier()
        val listener = LoopbackSignIn { got, error -> finish(app, got, error) }
        signIn?.stop()
        signIn = listener
        verifier = code
        // "Back to Ægentica" on OpenRouter's last page returns to the app's home.
        val back = "intent://home#Intent;scheme=aegentica;package=${app.packageName};end"
        val url = runCatching { listener.start(challenge(code), back) }.getOrElse { toast(app, "Sign-in could not start on this phone."); return }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            listener.stop(); toast(app, "No browser on this phone can open OpenRouter's sign-in.")
        }
    }

    private fun finish(app: Context, code: String?, error: String?) {
        val pkce = verifier
        signIn = null; verifier = null
        if (code == null || pkce == null) { toast(app, error ?: "Sign-in was cancelled."); return }
        Thread {
            val result = runCatching { exchange(code, pkce) }
            main.post {
                result.onSuccess { key -> ConnectedAI.saveOpenRouterKey(app, key); toast(app, "Signed in to OpenRouter. The phone assistant now uses the full agent.") }
                    .onFailure { toast(app, it.message ?: "OpenRouter did not give a key. Try again.") }
            }
        }.start()
    }

    /** Trades the sign-in code for a key. */
    fun exchange(code: String, verifier: String): String {
        val body = JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256").toString().toByteArray()
        val response = ConnectedAI.http.post(EXCHANGE, mapOf("Content-Type" to "application/json"), body) {}
        try {
            val text = response.body.readBounded(100_000).toString(Charsets.UTF_8)
            if (response.code !in 200..299) error(ConnectedAI.problem(response.code, text, "OpenRouter"))
            return JSONObject(text).optString("key").takeIf { it.isNotBlank() } ?: error("OpenRouter did not give a key. Try again.")
        } finally { response.close() }
    }

    private fun toast(app: Context, text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
}
