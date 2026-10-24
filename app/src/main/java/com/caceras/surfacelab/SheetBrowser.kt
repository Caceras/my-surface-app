package com.caceras.surfacelab

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the agent's browser may open: public web pages on the standard ports, never the phone's
 * own network (localhost, private ranges, link-local, carrier NAT, `.local` names), never an
 * address with a password in it, and never Ægentica's own sites, which open signed in inside the
 * app (Glimt's guard, apps/chatbar/web/server/guard.ts).
 */
object WebGuard {
    /** Test-only: name lookups without the network. */
    @Volatile var resolverForTest: ((String) -> List<InetAddress>)? = null

    private val OWN = AegenticaActivity.SITE.hosts + BarActivity.SITE.hosts

    /** Why [raw] may not open, or null when it may. Never looks a name up. */
    fun refuse(raw: String): String? {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return "That is not a web address."
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return "Only web pages can be opened."
        if (uri.rawUserInfo != null) return "Addresses with a password in them are not opened."
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return "Only pages on the standard web ports can be opened."
        val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]") ?: return "That is not a web address."
        if (host.isEmpty()) return "That is not a web address."
        if (host == "localhost" || listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa", ".home", ".corp").any { host.endsWith(it) } ||
            (!host.contains('.') && !host.contains(':'))) return LOCAL
        literal(host)?.let { if (!public(it)) return LOCAL }
        if (OWN.any { host == it || host.endsWith(".$it") } || host == "aegentica.se" || host.endsWith(".aegentica.se"))
            return "Ægentica's own sites open in the app, not in the agent's browser."
        return null
    }

    private const val LOCAL = "Pages on this phone's own network are not opened."

    /** An IP address written as the host, or null for a name. */
    fun literal(host: String): InetAddress? =
        if (host.matches(Regex("[0-9.]+")) || host.contains(':')) runCatching { InetAddress.getByName(host) }.getOrNull() else null

    /** Whether an address is on the public internet. */
    fun public(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 0xFF }
        if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) ||
            (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) || (b[0] == 203 && b[1] == 0 && b[2] == 113) || b[0] >= 224)
        // IPv6: unique local (fc00::/7), documentation, and an IPv4 address inside one.
        if ((b[0] and 0xFE) == 0xFC || (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8)) return false
        if (b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF) return public(InetAddress.getByAddress(b.drop(12).map { it.toByte() }.toByteArray()))
        return true
    }

    /** [refuse], and also a name that points at the phone's own network. Looks the name up: worker threads only. */
    fun refuseResolved(raw: String): String? {
        refuse(raw)?.let { return it }
        val host = Uri.parse(raw.trim()).host ?: return null
        if (literal(host) != null) return null
        val addresses = runCatching { (resolverForTest ?: { h: String -> InetAddress.getAllByName(h).toList() })(host) }.getOrNull() ?: return null
        return if (addresses.all(::public)) null else LOCAL
    }
}

/**
 * The browser inside the sheet that the phone agent browses in: up to [MAX_PAGES] pages, each its
 * own web view, kept for the conversation and shown in the sheet when it is open. The person sees
 * every page and can use it too (typing a password there themselves). Pages load only what
 * [WebGuard] allows; links that would open another app are not followed; no sign-in prompts, no
 * downloads, no third-party cookies. [WebTools] gives the agent its hands here.
 */
object SheetBrowser {
    const val MAX_PAGES = 4
    private const val LOAD_MS = 15_000L

    class Page(val id: Int, val web: WebView, val wrapper: MutableContextWrapper) {
        @Volatile var title = ""
        @Volatile var url = ""
        @Volatile var loading = false
        @Volatile var blocked: String? = null
        @Volatile internal var loaded: CountDownLatch? = null
        @Volatile internal var injected = false
        val host get() = Uri.parse(url).host.orEmpty().removePrefix("www.")
    }

    private val main = Handler(Looper.getMainLooper())
    /** Read and changed on the main thread only. */
    val pages = mutableListOf<Page>()
    @Volatile var active: Page? = null; private set
    private var nextId = 1
    /** The sheet, redrawn when pages open, load, switch or close. */
    var listener: (() -> Unit)? = null
    /** Test-only: page scripts without a web engine; gets the page and the call, returns the call's string result. */
    @Volatile var jsForTest: ((Page, String) -> String?)? = null

    private fun changed() = main.post { listener?.invoke() }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    /** Runs [block] on the main thread and waits for its result (from a worker thread). */
    private fun <T> await(timeoutMs: Long, block: ((T) -> Unit) -> Unit): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) error("SheetBrowser waits only on worker threads")
        val latch = CountDownLatch(1)
        var value: T? = null
        main.post { block { value = it; latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return value
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): Page {
        val wrapper = MutableContextWrapper(context.applicationContext)
        val web = WebView(wrapper)
        val page = Page(nextId++, web, wrapper)
        web.tag = "sheet-page-${page.id}"
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            userAgentString = "$userAgentString AegenticaAgent/1"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val why = WebGuard.refuse(url) ?: return false
                page.blocked = why; changed()
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Nothing on the page may reach the phone's own network, not even a picture or a script.
                val host = request.url.host?.lowercase() ?: return null
                val local = host == "localhost" || host.endsWith(".local") || WebGuard.literal(host.removePrefix("[").removeSuffix("]"))?.let { !WebGuard.public(it) } == true
                return if (local) WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                page.url = url; page.loading = true; page.injected = false; changed()
            }
            override fun onPageFinished(view: WebView, url: String) {
                page.url = url; page.title = view.title.orEmpty(); page.loading = false; page.injected = false
                page.loaded?.countDown(); changed()
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) = handler.cancel()
        }
        return page
    }

    /** Opens [url] (already checked) in the active page, or a new one; from any thread. Returns the page. */
    fun open(context: Context, url: String, newPage: Boolean): Page? {
        val page = await<Page?>(5000) { done ->
            val target = active.takeIf { !newPage } ?: run {
                if (pages.size >= MAX_PAGES) { pages.removeAt(0).also { if (it === active) active = null }.web.destroy() }
                create(context).also { pages += it }
            }
            target.blocked = null
            target.loaded = CountDownLatch(1)
            target.url = url; target.title = ""
            active = target
            target.web.loadUrl(url)
            changed()
            done(target)
        } ?: return null
        if (jsForTest == null) page.loaded?.await(LOAD_MS, TimeUnit.MILLISECONDS)
        return page
    }

    fun show(page: Page) = onMain { if (page in pages) { active = page; changed() } }

    /** Back one page in [page]'s history; false when there is none. From a worker thread. */
    fun back(page: Page): Boolean = jsForTest?.let { it(page, "back()") == "true" } ?: (await<Boolean>(3000) { done ->
        if (page.web.canGoBack()) { page.web.goBack(); done(true) } else done(false)
    } ?: false)

    fun close(page: Page) = onMain {
        if (!pages.remove(page)) return@onMain
        (page.web.parent as? ViewGroup)?.removeView(page.web)
        page.web.destroy()
        if (active === page) active = pages.lastOrNull()
        changed()
    }

    /** The conversation is over: every page goes. */
    fun closeAll() = onMain {
        pages.forEach { (it.web.parent as? ViewGroup)?.removeView(it.web); it.web.destroy() }
        pages.clear(); active = null; changed()
    }

    fun page(id: Int?): Page? = if (id == null) active else pages.firstOrNull { it.id == id }

    /** Puts the active page in [frame] (the sheet's browser), with [context] as its window's context. */
    fun attach(frame: ViewGroup, context: Context) {
        val page = active
        for (i in frame.childCount - 1 downTo 0) {
            val child = frame.getChildAt(i)
            if (child !== page?.web) frame.removeViewAt(i)
        }
        if (page == null) return
        page.wrapper.baseContext = context
        if (page.web.parent !== frame) {
            (page.web.parent as? ViewGroup)?.removeView(page.web)
            frame.addView(page.web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Sliding in from the side, as a new page in Glimt Web does.
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) { page.web.translationX = frame.width * 0.3f; page.web.alpha = 0f; page.web.animate().translationX(0f).alpha(1f).setDuration(200).start() }
        }
    }

    /** The sheet closes: pages stay for the conversation, out of its window. */
    fun detach(frame: ViewGroup) {
        pages.forEach { p -> if (p.web.parent === frame) frame.removeView(p.web); p.wrapper.baseContext = p.wrapper.applicationContext }
    }

    private var script: String? = null
    private fun script(context: Context) = script ?: context.assets.open("web-agent.js").use { it.readBytes().toString(Charsets.UTF_8) }.also { script = it }

    /**
     * Calls the page script (`__aeg.<call>`) in [page] and returns its string result; from a
     * worker thread. Null when the page did not answer in time.
     */
    fun js(context: Context, page: Page, call: String, timeoutMs: Long = 8000): String? {
        jsForTest?.let { return it(page, call) }
        val source = script(context) + "\n;window.__aeg ? window.__aeg.$call : null"
        val raw = await<String?>(timeoutMs) { done -> page.web.evaluateJavascript(source) { done(it) } } ?: return null
        // A string result comes back JSON-encoded.
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    /** A picture of the page as shown (a JPEG of at most 1568 px), from a worker thread. */
    fun screenshot(page: Page): ByteArray? = await<ByteArray?>(4000) { done ->
        val web = page.web
        val w = if (web.width > 0) web.width else 1080
        val h = if (web.height > 0) web.height else 1600
        if (web.width == 0) { web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); web.layout(0, 0, w, h) }
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { web.draw(android.graphics.Canvas(it)) } }.getOrNull()
        done(bitmap?.let { b ->
            val scale = minOf(1f, 1568f / maxOf(b.width, b.height))
            val out = java.io.ByteArrayOutputStream()
            Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true).compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        })
    }

    internal fun resetForTest() { closeAll(); jsForTest = null; WebGuard.resolverForTest = null; listener = null }
}

/**
 * The agent's hands in the sheet's browser, with Glimt's tiers: reading, finding, scrolling and
 * switching pages run on their own; opening a page, clicking, typing and choosing ask first (or
 * run on a site the person allowed always), and a button or form that sends, pays, deletes or
 * changes an account asks every time. Password, card and code fields are never typed into.
 */
class WebTools(private val context: Context, private val playbook: Playbook = Playbook.get(context)) : AgentToolbox {
    private val policy = HandsPolicy(playbook)
    private val prefs = context.getSharedPreferences(PREFS, 0)

    override fun offered(): List<ToolSpec> = playbook.tools.filter { it.target == "web" }

    private fun refused(text: String) = Prepared.Refused(ToolOutcome(false, text))

    override fun prepare(spec: ToolSpec, args: JSONObject): Prepared {
        val why = args.optString("why").oneLine()
        if (spec.needsWhy && (why.length < playbook.whyMin || why.length > playbook.whyMax))
            return refused("Give `why`: one short sentence (${playbook.whyMin} to ${playbook.whyMax} characters) saying what this step does.")
        if (spec.name == "web_open") {
            val url = args.optString("url").trim()
            WebGuard.refuseResolved(url)?.let { return refused(it) }
            val host = Uri.parse(url).host.orEmpty().removePrefix("www.")
            return Prepared.Ready(spec, args, spec.risk, spec.label(args), why, host, host, payload = url)
        }
        if (spec.name == "web_pages") return Prepared.Ready(spec, args, spec.risk, spec.label(args), why, "", "")
        val page = SheetBrowser.page(if (args.has("page")) args.optInt("page") else null)
            ?: return refused(if (args.has("page")) "There is no page ${args.optInt("page")}. Use web_pages." else "No page is open. Use web_open first.")
        var risk = spec.risk
        var reason: String? = null
        if (spec.name in setOf("web_click", "web_type", "web_select", "web_highlight") || (spec.name == "web_scroll" && args.optString("target").startsWith("e"))) {
            val ref = args.optString(if (spec.name == "web_scroll") "target" else "ref")
            val about = SheetBrowser.js(context, page, "about(${JSONObject.quote(ref)})")?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return refused("The page did not answer. Take a new snapshot.")
            if (!about.optBoolean("found")) return refused("There is no $ref on the page now. Take a new snapshot.")
            val label = about.optString("name")
            if (spec.name == "web_type") {
                if (about.optBoolean("secret") || policy.secretText(label, about.optString("id")))
                    return refused(TAKE_OVER)
                if (args.optString("text").length > 2000) return refused("That is too much text at once (at most 2,000 characters).")
                if (args.optBoolean("submit")) { risk = Risk.ALWAYS; reason = "It submits a form" }
            }
            if (spec.risk != Risk.READ) policy.sensitiveText(label, about.optString("id"))?.let { risk = Risk.ALWAYS; reason = it }
        }
        return Prepared.Ready(spec, args, risk, spec.label(args), why, page.host, page.host, reason, page)
    }

    override fun run(call: Prepared.Ready, done: (ToolOutcome) -> Unit) {
        Thread({ done(runCatching { exec(call) }.getOrElse { ToolOutcome(false, "The page did not answer: ${it.message ?: it.javaClass.simpleName}") }) }, "web-tool").start()
    }

    private fun json(raw: String?): JSONObject = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: error("no answer")

    private fun head(page: SheetBrowser.Page) = "Page ${page.id} · ${page.title.ifBlank { page.host }} · ${page.url}"

    private fun exec(call: Prepared.Ready): ToolOutcome {
        val a = call.args
        if (call.spec.name == "web_open") {
            val page = SheetBrowser.open(context, call.payload as String, a.optBoolean("new_page") || SheetBrowser.active == null)
                ?: return ToolOutcome(false, "The browser did not open.")
            page.blocked?.let { return ToolOutcome(false, it) }
            val text = SheetBrowser.js(context, page, "read(0)")?.let { runCatching { JSONObject(it) }.getOrNull() }
            return ToolOutcome(true, head(page) + "\n" + (text?.optString("text")?.take(2000)?.let { "The page begins:\n$it" } ?: "Opened. Read it before acting."))
        }
        if (call.spec.name == "web_pages") {
            val list = SheetBrowser.pages.toList()
            if (list.isEmpty()) return ToolOutcome(true, "No pages are open.")
            return ToolOutcome(true, list.joinToString("\n") { (if (it === SheetBrowser.active) "▸ " else "  ") + head(it) })
        }
        val page = call.payload as SheetBrowser.Page
        if (page !== SheetBrowser.active) SheetBrowser.show(page)
        return when (call.spec.name) {
            "web_read" -> {
                val r = json(SheetBrowser.js(context, page, "read(${a.optInt("offset", 0).coerceAtLeast(0)})"))
                ToolOutcome(true, head(page) + "\n" + r.optString("text") + (if (r.isNull("next")) "" else "\n[More: web_read with offset ${r.optInt("next")} of ${r.optInt("total")}]"))
            }
            "web_snapshot" -> {
                val r = json(SheetBrowser.js(context, page, "snapshot()"))
                val lines = r.optJSONArray("lines").strings()
                ToolOutcome(true, head(page) + "\n" + lines.joinToString("\n").ifBlank { "(Nothing to click or type into.)" } + (r.optInt("more").takeIf { it > 0 }?.let { "\n[$it more below]" } ?: ""))
            }
            "web_find" -> {
                val hits = json(SheetBrowser.js(context, page, "find(${JSONObject.quote(a.optString("query"))})")).optJSONArray("hits").strings()
                ToolOutcome(true, if (hits.isEmpty()) "Not found on the page." else hits.joinToString("\n"))
            }
            "web_scroll" -> { SheetBrowser.js(context, page, "scroll(${JSONObject.quote(a.optString("target").ifBlank { "down" })})"); ToolOutcome(true, "Scrolled.") }
            "web_highlight" -> { SheetBrowser.js(context, page, "mark(${JSONObject.quote(a.optString("ref"))})"); ToolOutcome(true, "Pointed at ${a.optString("ref")}.") }
            "web_screenshot" -> SheetBrowser.screenshot(page)?.let { ToolOutcome(true, "Screenshot of ${head(page)}", it) } ?: ToolOutcome(false, "No picture of the page.")
            "web_close" -> { SheetBrowser.close(page); ToolOutcome(true, "Closed page ${page.id}.") }
            "web_back" -> {
                if (!SheetBrowser.back(page)) return ToolOutcome(false, "This page has nothing to go back to.")
                settle(page); ToolOutcome(true, "Back. Now on ${head(page)}.")
            }
            "web_click", "web_type", "web_select" -> act(page, call)
            else -> ToolOutcome(false, "There is no tool called ${call.spec.name}.")
        }
    }

    /** An acting step: the element is outlined first, so the person sees it, then the step runs and the page settles. */
    private fun act(page: SheetBrowser.Page, call: Prepared.Ready): ToolOutcome {
        val a = call.args
        val ref = a.optString("ref")
        SheetBrowser.js(context, page, "mark(${JSONObject.quote(ref)})")
        if (SheetBrowser.jsForTest == null) Thread.sleep(400)
        val before = page.url
        val r = json(when (call.spec.name) {
            "web_click" -> SheetBrowser.js(context, page, "click(${JSONObject.quote(ref)})")
            "web_type" -> SheetBrowser.js(context, page, "type(${JSONObject.quote(ref)}, ${JSONObject.quote(a.optString("text"))}, ${a.optBoolean("submit")}, ${a.optBoolean("clear", true)})")
            else -> SheetBrowser.js(context, page, "select(${JSONObject.quote(ref)}, ${JSONObject.quote(a.optString("value"))})")
        })
        if (r.optBoolean("secret")) return ToolOutcome(false, TAKE_OVER)
        if (!r.optBoolean("ok")) return ToolOutcome(false, r.optString("error").ifBlank { "That did not work." })
        settle(page)
        val moved = page.url != before
        return ToolOutcome(true, (if (moved) "Now on ${head(page)}." else "Done. The page may have changed: take a new snapshot.") +
            (r.optString("chosen").takeIf { it.isNotBlank() }?.let { " Chose “$it”." } ?: ""))
    }

    /** A moment for the page to react; a new page waits until it has loaded. */
    private fun settle(page: SheetBrowser.Page) {
        if (SheetBrowser.jsForTest != null) return
        Thread.sleep(800)
        val until = System.nanoTime() + 10_000_000_000L
        while (page.loading && System.nanoTime() < until) Thread.sleep(150)
    }

    override fun remembered(call: Prepared.Ready) = call.risk == Risk.ACT && call.app.isNotBlank() && call.app in always(context)
    override fun remember(call: Prepared.Ready) { if (call.app.isNotBlank()) prefs.edit().putStringSet(ALWAYS, always(context) + call.app).apply() }

    companion object {
        const val PREFS = "web"
        private const val ALWAYS = "always"
        const val TAKE_OVER = "Never typed: this is a password, card or code field. Ask the person to type it in the page themselves, then continue."
        fun always(context: Context): Set<String> = context.getSharedPreferences(PREFS, 0).getStringSet(ALWAYS, emptySet()).orEmpty()
        fun forgetAlways(context: Context) = context.getSharedPreferences(PREFS, 0).edit().remove(ALWAYS).apply()
    }
}
