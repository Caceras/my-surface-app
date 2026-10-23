package com.caceras.surfacelab

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * OpenRouter sign-in in Chrome, where every sign-in method works (Google blocks sign-in inside an
 * app's web view). OpenRouter accepts a localhost callback on any port, so this listens on
 * 127.0.0.1 on a random free port for that one redirect, takes its code, answers with a short
 * page and stops: the loopback redirect for native apps (RFC 8252). Nothing else is served.
 */
class LoopbackSignIn(private val done: (code: String?, error: String?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    /** Start listening and return the OpenRouter address to open in the browser. */
    fun start(challenge: String, returnLink: String): Uri {
        stop()
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = TIMEOUT_MS }
        server = socket
        thread(name = "openrouter-sign-in", isDaemon = true) { serve(socket, returnLink) }
        return authUrl(challenge, socket.localPort)
    }

    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun serve(socket: ServerSocket, returnLink: String) {
        try {
            while (!socket.isClosed) {
                socket.accept().use { client ->
                    val target = requestTarget(client) ?: return@use
                    if (!target.startsWith("/callback")) { reply(client, 404, "Not found."); return@use }
                    val code = codeFrom(target)
                    reply(client, 200, page(code != null, returnLink))
                    finish(socket, code, if (code == null) "OpenRouter did not send a sign-in code." else null)
                    return
                }
            }
        } catch (_: SocketTimeoutException) {
            finish(socket, null, "Sign-in timed out. Try again.")
        } catch (_: IOException) {
            // Closed by stop(): a newer sign-in or the screen going away. Nothing to report.
        }
    }

    private fun finish(socket: ServerSocket, code: String?, error: String?) {
        runCatching { socket.close() }
        if (server === socket) server = null
        main.post { done(code, error) }
    }

    private fun requestTarget(client: Socket): String? {
        client.soTimeout = 5_000
        val line = client.getInputStream().bufferedReader().readLine() ?: return null
        val parts = line.split(' ')
        return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "GET" }
    }

    private fun reply(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    companion object {
        private const val TIMEOUT_MS = 10 * 60 * 1000

        fun authUrl(challenge: String, port: Int): Uri = Uri.parse("https://openrouter.ai/auth").buildUpon()
            .appendQueryParameter("callback_url", "http://localhost:$port/callback")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("key_label", "Ægentica")
            .build()

        /** The code from "/callback?code=…", or null. */
        fun codeFrom(target: String): String? =
            Uri.parse("http://localhost$target").getQueryParameter("code")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 }

        private fun page(signedIn: Boolean, returnLink: String): String {
            val title = if (signedIn) "Signed in" else "Sign-in did not finish"
            val text = if (signedIn) "You are signed in to OpenRouter. Go back to Ægentica to continue."
                else "OpenRouter did not send a code. Go back to Ægentica and try again."
            return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title>
<style>:root{color-scheme:light dark}body{margin:0;font:17px/1.5 system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;text-align:center;padding:24px;box-sizing:border-box}a{display:inline-block;margin-top:20px;padding:14px 26px;border-radius:999px;background:#141414;color:#fafafa;text-decoration:none;font-weight:600}@media (prefers-color-scheme:dark){a{background:#e5e5e5;color:#171717}}</style></head>
<body><main><h1>$title</h1><p>$text</p><a href="$returnLink">Back to Ægentica</a></main></body></html>"""
        }
    }
}
