package com.caceras.surfacelab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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

/** A site the app shows full screen: where it starts, which addresses stay in the app. */
class ShellSite(
    /** Named in the offline message. */
    val name: String,
    val home: String,
    /** https hosts that load inside the app; every other link opens in the browser. */
    val hosts: Set<String>,
    /** The web view's tag, for tests. */
    val tag: String,
    /** The page may use the microphone (live voice), after Android's own permission. */
    val microphone: Boolean,
    val lightCanvas: String,
    val darkCanvas: String,
) {
    fun keeps(url: Uri): Boolean = url.scheme == "https" && url.host in hosts && url.port == -1
}

/**
 * A site full screen, with the phone's powers lent over a private message channel
 * ([ShellBridge]): the phone's own voices (HayaiTTS and the like) through [ReadingService],
 * on-device dictation, the voice picker, OpenRouter sign-in in Chrome ([LoopbackSignIn]), the
 * app's own tools, and what other apps share. The site's code and design come from its server,
 * so a design update needs no new APK. Only the site's own addresses load here; every other link
 * opens in the browser, and the channel is posted to the loaded page's origin alone.
 */
abstract class ShellActivity : Activity(), ShellBridge.Phone {
    protected abstract val site: ShellSite
    /** The app's own tools, from the page's menu button. */
    protected abstract fun menuItems(): List<Pair<String, () -> Unit>>

    protected lateinit var web: WebView
    private lateinit var offline: View
    private val main = Handler(Looper.getMainLooper())
    private val bridge = ShellBridge({ json -> port?.postMessage(WebMessage(json)) }, this)
    private var channel: Array<WebMessagePort>? = null
    private val port get() = channel?.get(0)
    private var postedForLoad = false
    private var speakingDone: ((String?) -> Unit)? = null
    private val ears by lazy { Ears(this) }
    private val signIn = LoopbackSignIn { code, error -> signedIn?.invoke(code, error); signedIn = null }
    private var signedIn: ((String?, String?) -> Unit)? = null
    private var choosing: ValueCallback<Array<Uri>>? = null
    private var micRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val canvas = Color.parseColor(if (night()) site.darkCanvas else site.lightCanvas)
        web = WebView(this).apply {
            tag = site.tag
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
            tag = "${site.tag}-offline"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padDp(32, 32, 32, 32)
            visibility = View.GONE
            addView(label("Ægentica could not reach ${site.name}", 20f).apply { medium(); gravity = Gravity.CENTER })
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
        if (state == null || web.restoreState(state) == null) web.loadUrl(site.home)
        accept(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    /** What another app handed over: a share (link or text). A return link carries nothing. */
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
        // Keep the sign-in if Android closes the app while it is in the background.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        signIn.stop()
        ears.cancel()
        micRequest?.deny()
        if (speakingDone != null) ReadingService.listener = null
        channel?.forEach { runCatching { it.close() } }
        web.destroy()
        super.onDestroy()
    }

    /** Back steps through the site's pages first; on the first page it leaves. */
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
        choosing?.onReceiveValue(chosenFiles(resultCode, data))
        choosing = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != MIC) return
        val request = micRequest ?: return
        micRequest = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        else request.deny()
    }

    private fun night() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Hand the loaded page one end of a fresh channel, aimed at that page's origin only. */
    private fun connect(view: WebView, page: Uri) {
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
        view.postWebMessage(WebMessage(HANDSHAKE, arrayOf(ports[1])), Uri.parse("https://${page.host}"))
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (site.keeps(request.url)) return false
            openOutside(request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            postedForLoad = false
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBack()

        override fun onPageFinished(view: WebView, url: String?) {
            val page = url?.let { Uri.parse(it) } ?: return
            if (site.keeps(page)) connect(view, page)
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
                val pick = params.createIntent()
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                @Suppress("DEPRECATION") startActivityForResult(pick, PICK_FILES)
                true
            } catch (_: ActivityNotFoundException) {
                choosing = null
                false
            }
        }

        /**
         * The microphone, for a site that uses it (Ægentica's live voice), and only for its own
         * pages; Android asks first. Camera, location and everything else stay refused.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE
            if (!site.microphone || !site.keeps(request.origin) || request.resources.any { it != mic }) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(mic))
                return
            }
            micRequest?.deny()
            micRequest = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (micRequest === request) micRequest = null
        }
    }

    // ---------------------------------------------------------------- the phone, for the page

    override fun speak(text: String, language: String, done: (String?) -> Unit) {
        // A newer answer replaces the one being read.
        speakingDone?.invoke(null)
        speakingDone = done
        ReadingService.listener = { s ->
            // A pause keeps the reading open; the end, closing the reader or a problem ends it.
            if (s.ended || s.error.isNotBlank()) {
                ReadingService.listener = null
                val finished = speakingDone
                speakingDone = null
                finished?.invoke(s.error.ifBlank { null })
            }
        }
        ReadingService.play(this, text, language)
    }

    override fun pauseSpeaking() = ReadingService.pause(this)

    override fun resumeSpeaking() = ReadingService.resume(this)

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

    override fun chooseVoice() = SpeechVoices.showPicker(this) { ReadingService.refreshVoice() }

    override fun menu() {
        val items = menuItems()
        AlertDialog.Builder(this).setTitle(ShellBridge.APP)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .showProtected(this)
    }

    /** The phone's tools shared by every site. */
    protected fun phoneTools(): List<Pair<String, () -> Unit>> = listOf(
        "Notes and tasks" to { startActivity(Intent(this, HomeActivity::class.java)) },
        "Record and transcribe" to { startActivity(TranscribeActivity.intent(this)) },
        "Talk hands-free" to { startActivity(Intent(this, VoiceActivity::class.java)) },
        "Reading voice" to { chooseVoice() },
        "Phone assistant, keyboard and settings" to {
            startActivity(Intent(this, MainActivity::class.java).putExtra("settings", true))
        },
    )

    companion object {
        /** Pages read this to know they are inside the app; every capability still needs the channel. */
        const val USER_AGENT = "AegenticaShell/1"
        const val HANDSHAKE = "aegentica-shell"
        private const val PICK_FILES = 31
        private const val MIC = 32

        /** One file or several (a page's multiple file input), or none when cancelled. */
        fun chosenFiles(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != RESULT_OK) return null
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
            return data?.data?.let { arrayOf(it) }
        }
    }
}
