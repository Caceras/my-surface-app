package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * A job on aegentica.se as a card, the same in the sheet and the genie: what it is doing, its
 * steps, a question or an approval (with the price when a step costs money, approved by a tap on
 * the amount), tables filling in, pictures, files, the cost so far, the server's browser live,
 * and when it ends, its answer. Everything shown is data from the job; links open only https
 * pages, aegentica.se's inside the app.
 */
class JobCard(private val context: Context, private val host: Host) {
    /** What the surface around the card does: put its composer on the job, and close itself. */
    interface Host {
        /** The next words in the composer go to the job (or answer [input]). */
        fun tell(job: ServerJobs.Job, input: ServerJobs.Input?)
        fun opened() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun ink(id: Int) = context.getColor(id)

    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "job-card"; visibility = View.GONE; setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(ink(R.color.ask_fill)) } }
    @Volatile var job: ServerJobs.Job? = null; private set
    private var live: LiveBrowser? = null
    private var liveImage: LiveImage? = null
    private val pictures = HashMap<String, Bitmap?>()
    private var answered: String? = null
    private var error: String? = null

    private val observer: (ServerJobs.Job) -> Unit = { if (it === job) render() }

    /** Follow [job] (null hides the card). */
    fun show(next: ServerJobs.Job?) {
        if (next !== job) { stopLive(); answered = null; error = null }
        job = next
        ServerJobs.addObserver(observer)
        render()
    }

    /** The surface closes: no more updates, and the live view's stream closes. */
    fun detach() { ServerJobs.removeObserver(observer); stopLive() }

    private fun text(value: CharSequence, size: Float, tone: Int = R.color.ask_ink, tagName: String? = null) = TextView(context).apply {
        text = value; textSize = size; setTextColor(ink(tone)); tag = tagName; setLineSpacing(0f, 1.15f)
    }

    private fun button(title: String, tagName: String, primary: Boolean, run: () -> Unit) = TextView(context).apply {
        text = title; tag = tagName; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(14), 0, dp(14), 0); isClickable = true; isFocusable = true
        setTextColor(ink(if (primary) R.color.ask_on_ink else R.color.ask_ink))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(ink(if (primary) R.color.ask_ink else R.color.ask_surface)) }
        setOnClickListener { run() }
    }

    private fun row(views: List<View>) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6); topMargin = dp(6) }) }
        })
    }

    private fun stateWords(job: ServerJobs.Job) = when (job.state) {
        "input_required" -> "Waiting for you"
        "completed" -> "Done"
        "failed" -> "Could not finish"
        "cancelled" -> "Stopped"
        else -> "Working"
    }

    fun render() {
        val job = job
        view.removeAllViews()
        if (job == null) { view.visibility = View.GONE; stopLive(); return }
        view.visibility = View.VISIBLE
        val head = "On aegentica.se · ${stateWords(job)}" + (job.cost?.let { String.format(Locale.ROOT, " · $%.2f", it) } ?: "")
        view.addView(text(head, 12f, R.color.ask_ink_3, "job-state"))
        if (job.title.isNotBlank()) view.addView(text(job.title, 15f, tagName = "job-title").apply { medium(); maxLines = 2 })
        job.steps.toList().takeLast(4).forEach { step ->
            val mark = when (step.status) { "done" -> "✓"; "error" -> "!"; "waiting" -> "?"; else -> "•" }
            view.addView(text("$mark  ${step.label}", 13f, if (step.status == "running") R.color.ask_ink_2 else R.color.ask_ink_3, "job-step").apply { maxLines = 2; setPadding(0, dp(2), 0, dp(2)) })
        }
        job.inputs.firstOrNull()?.let { input(job, it) }
        job.tablesNow().take(2).forEach { table(job, it) }
        pictures(job)
        browser(job)
        job.result?.takeIf { job.finished && it.isNotBlank() }?.let { result ->
            view.addView(text(Markdown.render(Prompts.reply(result), dp(18)), 15f, tagName = "job-result").apply { setTextIsSelectable(true); maxLines = 14; setPadding(0, dp(8), 0, 0) })
        }
        (job.error ?: error)?.let { view.addView(text(it, 13f, R.color.ask_ink_3, "job-error").apply { setPadding(0, dp(6), 0, 0) }) }
        val actions = mutableListOf<View>()
        job.files.toList().take(4).forEach { f -> actions += button(f.name.take(28), "job-file", false) { open(f.url) } }
        if (!job.finished) {
            // While an approval waits it is answered first: words would cancel it.
            if (ServerJobs.waitingApproval(job) == null) actions += button("Tell the job", "job-tell", false) { host.tell(job, job.inputs.firstOrNull { it.kind == "question" }) }
            actions += button("Stop", "job-stop", false) { ServerJobs.cancel(context, job) { e -> error = e ?: "Stopping…"; render() } }
        }
        actions += button("Open chat", "job-open", false) { open(job.open) }
        view.addView(row(actions))
    }

    /** An approval or a question. A priced approval shows its amount on the button that approves it. */
    private fun input(job: ServerJobs.Job, input: ServerJobs.Input) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "job-input"; setPadding(0, dp(8), 0, 0) }
        box.addView(text(input.prompt, 15f, tagName = "job-prompt").apply { medium() })
        ServerJobs.price(input)?.let { box.addView(text(it, 14f, R.color.ask_ink_2, "job-price")) }
        if (answered == input.requestId) { box.addView(text("Sending your answer…", 13f, R.color.ask_ink_3)); view.addView(box); return }
        val buttons = mutableListOf<View>()
        fun answer(optionId: String?) { answered = input.requestId; render(); ServerJobs.respond(context, job, input.requestId, optionId) { e -> if (e != null) { answered = null; error = e }; render() } }
        if (input.kind == "tool-approval") {
            val yes = ServerJobs.approveOption(input)
            val no = ServerJobs.declineOption(input)
            if (yes != null) buttons += button(ServerJobs.price(input)?.let { "Approve · ${it.removePrefix("About ")}" } ?: yes.label.ifBlank { "Approve" }, "job-approve", true) { answer(yes.id) }
            if (no != null) buttons += button("Don't", "job-deny", false) { answer(no.id) }
            input.options.filter { it != yes && it != no }.take(2).forEach { o -> buttons += button(o.label, "job-option", false) { answer(o.id) } }
        } else {
            input.options.take(4).forEach { o -> buttons += button(o.label, "job-option", false) { answer(o.id) } }
            if (input.freeform) buttons += button("Answer", "job-answer", input.options.isEmpty()) { host.tell(job, input) }
        }
        box.addView(row(buttons))
        view.addView(box)
    }

    /** A table as it fills in: its title, how many rows, and the newest few. */
    private fun table(job: ServerJobs.Job, table: ServerJobs.Table) {
        val rows = synchronized(job.tables) { table.rows.toList() }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; tag = "job-table"; setPadding(0, dp(8), 0, 0); isClickable = true
            setOnClickListener { open(table.url ?: job.open) }
            contentDescription = "${table.title}, ${rows.size} rows. Open it on aegentica.se."
        }
        box.addView(text("${table.title} · ${rows.size} ${if (rows.size == 1) "row" else "rows"}", 13f, R.color.ask_ink_2).apply { medium() })
        if (table.columns.isNotEmpty()) box.addView(text(table.columns.take(3).joinToString("  ·  "), 12f, R.color.ask_ink_3).apply { maxLines = 1 })
        rows.takeLast(4).forEach { r -> box.addView(text(r.take(3).joinToString("  ·  "), 13f, tagName = "job-row").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        view.addView(box)
    }

    /** The newest pictures, small; a tap opens the chat with all of them. */
    private fun pictures(job: ServerJobs.Job) {
        val newest = job.pictures.toList().takeLast(6)
        if (newest.isEmpty()) return
        val strip = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        newest.forEach { p ->
            val image = ImageView(context).apply {
                tag = "job-picture"; scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = p.alt ?: "A picture from the job"
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(ink(R.color.ask_surface)) }; clipToOutline = true
                isClickable = true; setOnClickListener { open(job.open) }
            }
            pictures[p.id]?.let { image.setImageBitmap(it) } ?: load(p)
            strip.addView(image, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(6) })
        }
        view.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; setPadding(0, dp(8), 0, 0); addView(strip) })
        if (job.pictures.size > newest.size) view.addView(text("${job.pictures.size} pictures", 12f, R.color.ask_ink_3))
    }

    private fun load(p: ServerJobs.Picture) {
        if (pictures.containsKey(p.id)) return
        pictures[p.id] = null
        Thread({
            val bitmap = runCatching { (imagesForTest ?: ::fetch)(context, p.url) }.getOrNull()
            main.post { if (bitmap != null) { pictures[p.id] = bitmap; render() } }
        }, "job-picture").start()
    }

    /** The server's browser while the job uses it: a small live picture; a tap opens it full screen. */
    private fun browser(job: ServerJobs.Job) {
        val b = job.browser
        if (b == null || !b.active || job.finished) { stopLive(); return }
        val browser = live ?: LiveBrowser(context).also { live = it; it.start() }
        val image = liveImage ?: LiveImage(context).also { liveImage = it }
        (image.parent as? ViewGroup)?.removeView(image)
        val line = text("", 12f, R.color.ask_ink_3, "job-live-status").apply { maxLines = 1 }
        fun describe() { line.text = listOfNotNull(browser.action?.label, b.title ?: browser.status?.title ?: b.url).joinToString(" · ").ifBlank { "Opening the browser…" } }
        browser.listener = { image.show(browser); describe() }
        image.show(browser); describe()
        image.tag = "job-live"; image.contentDescription = "Ægentica's browser, live. Tap to watch full screen or take over."
        image.setOnClickListener { host.opened(); context.startActivity(LiveViewActivity.intent(context)) }
        val frame = FrameLayout(context).apply { setPadding(0, dp(8), 0, 0) }
        frame.addView(image, FrameLayout.LayoutParams(dp(208), dp(130)))
        view.addView(frame)
        view.addView(line)
    }

    private fun stopLive() { live?.listener = null; live?.stop(); live = null }

    private fun open(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        host.opened()
        val intent = if (AegenticaActivity.isAegentica(uri)) AegenticaActivity.open(context, url)
            else Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        /** Test-only: pictures without the network. */
        @Volatile var imagesForTest: ((Context, String) -> Bitmap?)? = null

        /** A small picture from an https address; aegentica.se's own with this phone's token. */
        fun fetch(context: Context, url: String): Bitmap? {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 10_000; conn.readTimeout = 20_000
            if (AegenticaActivity.isAegentica(Uri.parse(url))) Backend.token(context)?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            try {
                if (conn.responseCode != 200 || conn.contentType?.startsWith("image/") != true) return null
                val bytes = conn.inputStream.readBounded(8_000_000)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally { conn.disconnect() }
        }
    }
}

/**
 * The live browser's picture, with a ring where the agent is about to act (the relay's x and y,
 * fractions of the page), so the owner sees what will happen next.
 */
@android.annotation.SuppressLint("AppCompatCustomView") // The core build has no AppCompat; a plain ImageView is enough here.
class LiveImage(context: Context) : ImageView(context) {
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * context.resources.displayMetrics.density; color = context.getColor(R.color.ask_ink)
    }
    private val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL; color = 0x33FFFFFF }
    private var point: Pair<Float, Float>? = null

    init {
        scaleType = ScaleType.FIT_CENTER; isClickable = true
        background = GradientDrawable().apply { cornerRadius = 12 * context.resources.displayMetrics.density; setColor(context.getColor(R.color.ask_ink)) }
        clipToOutline = true
    }

    fun show(browser: LiveBrowser) {
        browser.frame?.let { if (drawable == null || (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== it) setImageBitmap(it) }
        val a = browser.action
        point = if (a != null && a.phase in setOf("point", "act") && a.x != null && a.y != null) a.x to a.y else null
        invalidate()
    }

    /** Where a point of the page (fractions) is on this view, given FIT_CENTER. */
    fun toView(fx: Float, fy: Float): Pair<Float, Float>? {
        val d = drawable ?: return null
        val values = FloatArray(9); imageMatrix.getValues(values)
        return (values[android.graphics.Matrix.MTRANS_X] + paddingLeft + fx * d.intrinsicWidth * values[android.graphics.Matrix.MSCALE_X]) to
            (values[android.graphics.Matrix.MTRANS_Y] + paddingTop + fy * d.intrinsicHeight * values[android.graphics.Matrix.MSCALE_Y])
    }

    /** The page point (fractions) under a touch on this view, or null outside the picture. */
    fun toPage(x: Float, y: Float): Pair<Float, Float>? {
        val d = drawable ?: return null
        val values = FloatArray(9); imageMatrix.getValues(values)
        val w = d.intrinsicWidth * values[android.graphics.Matrix.MSCALE_X]; val h = d.intrinsicHeight * values[android.graphics.Matrix.MSCALE_Y]
        if (w <= 0f || h <= 0f) return null
        val fx = (x - paddingLeft - values[android.graphics.Matrix.MTRANS_X]) / w
        val fy = (y - paddingTop - values[android.graphics.Matrix.MTRANS_Y]) / h
        return if (fx in 0f..1f && fy in 0f..1f) fx to fy else null
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val (fx, fy) = point ?: return
        val (x, y) = toView(fx, fy) ?: return
        val r = 14 * resources.displayMetrics.density
        canvas.drawCircle(x, y, r, halo); canvas.drawCircle(x, y, r, ring)
    }
}

/**
 * A step that costs money, by voice: the amount is read aloud first, and only after it was heard
 * in full may a short, clear yes approve it; a clear no declines it. Anything else, a voice that
 * cannot speak, or a microphone that is not allowed leaves the buttons, which always work.
 */
object PriceVoice {
    private var ears: Ears? = null
    private var mouth: Mouth? = null

    fun ask(context: Context, job: ServerJobs.Job, input: ServerJobs.Input, mouth: Mouth, ears: Ears, status: (String) -> Unit) {
        if (!input.priced || input.requestId in job.readBack) return
        cancel()
        this.mouth = mouth; this.ears = ears
        mouth.onProblem = { mouth.onIdle = null; mouth.onProblem = null; status("Tap Approve to pay, or Don't.") }
        mouth.onIdle = {
            mouth.onIdle = null; mouth.onProblem = null
            // Only now has the owner heard the amount.
            job.readBack += input.requestId
            if (job.inputs.any { it.requestId == input.requestId } &&
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED && ears.available()) {
                status("Listening… say approve, or no.")
                ears.listen(onFinal = { heard -> decide(context, job, input, heard, status) }, onStop = { problem -> problem?.let { status("Tap Approve to pay, or Don't.") } })
            }
        }
        mouth.begin(ears.locale())
        mouth.finish(ServerJobs.priceWords(input))
    }

    internal fun decide(context: Context, job: ServerJobs.Job, input: ServerJobs.Input, heard: String, status: (String) -> Unit) {
        if (job.inputs.none { it.requestId == input.requestId }) return
        val readBack = input.requestId in job.readBack
        val option = when {
            ServerJobs.spokenApproval(heard, readBack) -> ServerJobs.approveOption(input)
            ServerJobs.spokenDecline(heard) -> ServerJobs.declineOption(input)
            else -> null
        }
        if (option == null) { status("I did not catch a clear yes. Tap Approve to pay, or Don't."); return }
        status(if (option == ServerJobs.approveOption(input)) "Approved. ${ServerJobs.price(input) ?: ""}".trim() else "Skipped.")
        ServerJobs.respond(context, job, input.requestId, option.id) { e -> e?.let(status) }
    }

    /** Stops listening for an answer; with [owner], only when that surface's ears were listening. */
    fun cancel(owner: Ears? = null) {
        if (owner != null && owner !== ears) return
        mouth?.let { it.onIdle = null; it.onProblem = null }
        ears?.cancel()
        mouth = null; ears = null
    }
}
