package com.caceras.surfacelab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Ægentica's home: Glimt (the chat bar from AwesomeSEO, as a web app) full screen, with the
 * phone's powers lent over a private message channel ([GlimtBridge]): the phone's own voices
 * (HayaiTTS and the like) through [ReadingService], on-device dictation, OpenRouter sign-in in
 * Chrome ([LoopbackSignIn]), and what other apps share. Glimt's code and design come from its
 * server, so a design update needs no new APK. Only Glimt's own address loads here; every other
 * link opens in the browser, and the channel is posted to Glimt's origin alone.
 */
class GlimtActivity : Activity(), GlimtBridge.Phone {
    private lateinit var web: WebView
    private lateinit var offline: View
    private val main = Handler(Looper.getMainLooper())
    private val bridge = GlimtBridge({ json -> port?.postMessage(WebMessage(json)) }, this)
    private var channel: Array<WebMessagePort>? = null
    private val port get() = channel?.get(0)
    private var postedForLoad = false
    private var speakingDone: ((String?) -> Unit)? = null
    private val ears by lazy { Ears(this) }
    private val signIn = LoopbackSignIn { code, error -> signedIn?.invoke(code, error); signedIn = null }
    private var signedIn: ((String?, String?) -> Unit)? = null
    private var choosing: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val canvas = Color.parseColor(if (night()) "#000000" else "#F5F5F7")
        web = WebView(this).apply {
            tag = "glimt"
            setBackgroundColor(canvas)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = "${settings.userAgentString} $USER_AGENT"
            webViewClient = Client()
            webChromeClient = Chrome()
        }
        offline = LinearLayout(this).apply {
            tag = "glimt-offline"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padDp(32, 32, 32, 32)
            visibility = View.GONE
            addView(label("Ægentica could not reach Glimt", 20f).apply { medium(); gravity = Gravity.CENTER })
            addView(label("Check your connection, then try again.", 15f, true).apply { gravity = Gravity.CENTER; padDp(0, 8, 0, 20) })
            addView(pill("Try again", primary = true) { visibility = View.GONE; web.reload() })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(canvas)
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(offline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            padForSystemBars()
        }
        setContentView(root)
        readableSystemBars()
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = canvas
            window.navigationBarColor = canvas
        }
        if (state == null || web.restoreState(state) == null) web.loadUrl(URL)
        accept(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    /** What another app handed over: a share (link or text). Sign-in's return link carries nothing. */
    private fun accept(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        bridge.context("share", text = text, title = intent.getStringExtra(Intent.EXTRA_SUBJECT))
        // Consume it, so Recents or recreation does not hand it over again.
        intent.action = Intent.ACTION_MAIN
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        ears.cancel()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        signIn.stop()
        ears.cancel()
        if (speakingDone != null) ReadingService.listener = null
        channel?.forEach { runCatching { it.close() } }
        web.destroy()
        super.onDestroy()
    }

    /** Back steps through Glimt's pages first; on the first page it leaves the app. */
    private var backRegistered = false
    private val back: Any? = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { web.goBack() } else null

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = web.canGoBack()
        if (want == backRegistered) return
        val callback = back as OnBackInvokedCallback
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        backRegistered = want
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Before Android 13 Back arrives as a key.
        if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES) return
        choosing?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        choosing = null
    }

    private fun night() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Hand Glimt's page one end of a fresh channel, aimed at Glimt's origin only. */
    private fun connect(view: WebView) {
        if (postedForLoad) return
        val ports = runCatching { view.createWebMessageChannel() }.getOrNull() ?: return
        postedForLoad = true
        channel?.forEach { runCatching { it.close() } }
        channel = ports
        bridge.reset()
        ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                message.data?.let { bridge.receive(it) }
            }
        }, main)
        view.postWebMessage(WebMessage(HANDSHAKE, arrayOf(ports[1])), ORIGIN)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (isGlimt(request.url)) return false
            openOutside(request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            postedForLoad = false
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBack()

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && isGlimt(Uri.parse(url))) connect(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) offline.visibility = View.VISIBLE
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            choosing?.onReceiveValue(null)
            choosing = callback
            return try {
                @Suppress("DEPRECATION") startActivityForResult(params.createIntent(), PICK_FILES)
                true
            } catch (_: ActivityNotFoundException) {
                choosing = null
                false
            }
        }
        // Camera and microphone requests from the page are refused: dictation is native, and
        // nothing else in Glimt needs them. (The default already denies; stated for the reader.)
    }

    // ---------------------------------------------------------------- the phone, for Glimt

    override fun speak(text: String, language: String, done: (String?) -> Unit) {
        // A newer answer replaces the one being read.
        speakingDone?.invoke(null)
        speakingDone = done
        var started = false
        ReadingService.listener = { s ->
            if (s.playing) started = true
            else if (started || s.error.isNotBlank()) {
                ReadingService.listener = null
                val finished = speakingDone
                speakingDone = null
                finished?.invoke(s.error.ifBlank { null })
            }
        }
        ReadingService.play(this, text, language)
    }

    override fun stopSpeaking() {
        ReadingService.listener = null
        speakingDone = null
        ReadingService.stop(this)
    }

    override fun listen(words: (String, Boolean) -> Unit, done: (String?) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
            done("Allow the microphone, then tap the mic again.")
            return
        }
        if (!ears.available()) {
            done("This phone has no on-device speech recognition. Type instead.")
            return
        }
        ears.cancel()
        ears.listen(
            onPartial = { words(it, false) },
            onFinal = { words(it, true) },
            onStop = { problem -> done(problem?.message) }
        )
    }

    override fun stopListening() = ears.stop()

    override fun signIn(challenge: String, done: (String?, String?) -> Unit) {
        signedIn?.invoke(null, "A newer sign-in started.")
        signedIn = done
        val back = "intent://open#Intent;scheme=aegentica;package=$packageName;end"
        val url = runCatching { signIn.start(challenge, back) }.getOrElse {
            signedIn = null
            done(null, "Sign-in could not start on this phone.")
            return
        }
        openOutside(url.toString())
    }

    override fun openOutside(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // Nothing can open it; the page stays as it is.
        }
    }

    /** The app's own tools, behind the mark beside Glimt's address. */
    override fun menu() {
        val items = listOf<Pair<String, () -> Unit>>(
            "Notes and tasks" to { startActivity(Intent(this, HomeActivity::class.java)) },
            "Record and transcribe" to { startActivity(TranscribeActivity.intent(this)) },
            "Talk hands-free" to { startActivity(Intent(this, VoiceActivity::class.java)) },
            "Reading voice" to { SpeechVoices.showPicker(this) { ReadingService.refreshVoice() } },
            "Phone assistant, keyboard and settings" to {
                startActivity(Intent(this, MainActivity::class.java).putExtra("settings", true))
            },
        )
        AlertDialog.Builder(this).setTitle(GlimtBridge.APP)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .showProtected(this)
    }

    companion object {
        /** Glimt Web on the owner's server (Dokploy project awesomeseo, compose glimt-web). */
        const val URL = "https://glimt-136-148-209-184.sslip.io/"
        val ORIGIN: Uri = Uri.parse("https://glimt-136-148-209-184.sslip.io")
        /** Glimt reads this to know it is inside the app; every capability still needs the channel. */
        const val USER_AGENT = "AegenticaShell/1"
        const val HANDSHAKE = "aegentica-shell"
        private const val PICK_FILES = 31
        private const val MIC = 32

        fun isGlimt(url: Uri): Boolean = url.scheme == "https" && url.host == ORIGIN.host && url.port == -1

        fun intent(context: Context) = Intent(context, GlimtActivity::class.java)
    }
}
