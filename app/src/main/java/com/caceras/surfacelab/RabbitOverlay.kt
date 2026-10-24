package com.caceras.surfacelab

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.caceras.surfacelab.RabbitMotion.Pose
import kotlin.math.abs

/**
 * Ægentica over every app: the rabbit (Glimt's 13 sprites from Riki's own drawings, with its
 * motion rules in [RabbitMotion]) and a floating bar in aegentica.se's monochrome composer shape,
 * drawn by Ægentica hands' accessibility service so it stays while the owner keeps using the
 * phone. The rabbit has no name.
 *
 * Three forms, moved between with short scale-and-fade transitions: the **bubble** (just the
 * rabbit, with Stop while it works), the **bar** (what it is doing, the message box, + / screen /
 * model / microphone / send) and the **expanded** bar (the question, the steps, the answer and Allow
 * prompts above the box). Drag the rabbit to move it; tap it to fold or unfold; hold it to hear
 * what it is doing. The microphone can stay open while it works: what the owner says steers the
 * run, and talking over its voice stops the voice.
 *
 * While the hands act, a full-screen stage that never takes a touch shows the moves: the rabbit
 * hops (or leaps, further away) to land beside the target before each step, acts it out (a
 * pounce for a tap or typing, a look for reading, a walk for a scroll, a leap to another screen),
 * rings it, ripples a tap and poofs when a new screen opens. Between steps it takes the pose that
 * fits what Ægentica is doing, and while it waits it fidgets now and then. A touch anywhere else while the
 * hands work pauses them. Only transform and alpha animate, and nothing moves when Android's
 * animations are off.
 */
class RabbitOverlay(private val context: Context) : HandsOverlay {

    enum class Form { HIDDEN, BUBBLE, BAR, EXPANDED }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    /** The bubble's size. */
    private val size get() = dp(56)
    /** A sprite's size on screen, in pixels. */
    private fun spriteW(p: Pose) = (p.w * RabbitMotion.SCALE * density).toInt()
    private fun spriteH(p: Pose) = (p.h * RabbitMotion.SCALE * density).toInt()
    private fun ink(id: Int) = context.getColor(id)

    var form = Form.HIDDEN; private set
    /** What the expanded card shows below the answer: an Allow prompt, "you have the screen", or nothing. */
    internal var mode = "status"; private set

    private var stage: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var flyer: ImageView
    private lateinit var bubble: FrameLayout
    private lateinit var bubbleRabbit: ImageView
    private lateinit var bubbleStop: TextView
    private lateinit var bar: LinearLayout
    private lateinit var barRabbit: ImageView
    private lateinit var status: TextView
    private lateinit var stop: TextView
    private lateinit var input: EditText
    private lateinit var screenChip: TextView
    private lateinit var model: ImageButton
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton
    private lateinit var card: LinearLayout
    private lateinit var question: TextView
    private lateinit var steps: LinearLayout
    private lateinit var answer: TextView
    private lateinit var extra: LinearLayout
    private lateinit var actions: LinearLayout

    private var session: AgentSession? = null
    private val observer: (AgentSession) -> Unit = { render(it) }
    private var away = false
    private var returning: Runnable? = null
    private var leaving: Runnable? = null
    private var approvalShown: String? = null
    private var settledFor: AgentSession? = null
    private var mouth: Mouth? = null
    private var ears: Ears? = null
    /** The microphone stays open while it works: each thing said steers or asks. */
    internal var conversation = false; private set
    private var listening = false
    private var micDownAt = 0L
    /** The screen the owner summoned the genie on goes with the first question only. */
    private var screenPending = false
    private var screenOn = true
    private var unlistenScreen: (() -> Unit)? = null
    /** The invocation the genie was summoned for, whose screen it drops when it closes. */
    private var summoned = -1
    /** A job handed to the server agent on aegentica.se, in the expanded card. */
    private lateinit var jobs: JobCard
    /** The bar's next words go to this job (or answer its question). */
    private var telling: Pair<ServerJobs.Job, ServerJobs.Input?>? = null
    private var shownInput: String? = null
    private val jobWatcher: (ServerJobs.Job) -> Unit = { job -> if (showing) jobChanged(job) }

    val showing get() = panel != null
    private val motion get() = ValueAnimator.areAnimatorsEnabled()

    // ---------------------------------------------------------------- windows

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** A ring that shows on any app: a light halo under a dark line. */
    private fun ring(shape: Int, radius: Float = 0f) = LayerDrawable(arrayOf(
        GradientDrawable().apply { this.shape = shape; cornerRadius = radius; setStroke(dp(4), 0xCCFFFFFF.toInt()) },
        GradientDrawable().apply { this.shape = shape; cornerRadius = radius; setStroke(dp(2), 0xFF111114.toInt()) },
    ))

    private fun surface(radius: Int, translucent: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        // The bar floats lightly over the app; text sits on the opaque surface.
        val base = ink(R.color.ask_surface)
        setColor(if (translucent) (base and 0x00FFFFFF) or (0xEB shl 24) else base)
        setStroke(dp(1), ink(R.color.ask_edge))
    }

    private fun icon(drawable: Int, description: String, tagName: String, size: Int = 18, run: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(ink(R.color.ask_ink_2)); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description; tag = tagName; background = null
        val side = (dp(40) - dp(size)) / 2; setPadding(side, side, side, side)
        setOnClickListener { run() }
    }

    private fun text(value: String, sizeSp: Float, tone: Int = R.color.ask_ink) = TextView(context).apply {
        text = value; textSize = sizeSp; setTextColor(ink(tone)); setLineSpacing(0f, 1.2f)
    }

    private fun addWindows() {
        val stageView = FrameLayout(context).apply { tag = "rabbit-stage"; clipChildren = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        flyer = ImageView(context).apply { tag = "rabbit-flyer"; setImageResource(Pose.HOP.drawable); scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.INVISIBLE }
        stageView.addView(flyer, FrameLayout.LayoutParams(spriteW(Pose.HOP), spriteH(Pose.HOP)))
        wm.addView(stageView, overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        })
        stage = stageView

        // The bubble: the rabbit alone, and Stop while it works.
        bubbleRabbit = ImageView(context).apply { setImageResource(Pose.SIT.drawable); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        bubbleStop = stopPill("rabbit-bubble-stop")
        bubble = FrameLayout(context).apply {
            tag = "rabbit-bubble"; visibility = View.GONE
            contentDescription = "Ægentica. Tap to open, hold to hear what it is doing, drag to move."
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink(R.color.ask_surface)); setStroke(dp(1), ink(R.color.ask_edge)) }
                    elevation = dp(4).toFloat()
                    addView(bubbleRabbit, FrameLayout.LayoutParams(size, size))
                }, LinearLayout.LayoutParams(size, size))
                addView(bubbleStop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
            })
        }
        draggable(bubble) { expand(content = session?.thread?.isNotEmpty() == true) }

        // The bar: aegentica.se's composer shape.
        barRabbit = ImageView(context).apply {
            tag = "rabbit-home"; setImageResource(Pose.SIT.drawable); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Ægentica. Tap to fold into the rabbit, hold to hear what it is doing, drag to move."
        }
        draggable(barRabbit) { collapse() }
        status = text("", 13f, R.color.ask_ink_3).apply { tag = "rabbit-status"; maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setPadding(dp(6), 0, dp(6), 0) }
        stop = stopPill("rabbit-stop")
        input = EditText(context).apply {
            tag = "rabbit-input"; hint = "Ask Ægentica"; textSize = 16f; isSingleLine = true; background = null
            setTextColor(ink(R.color.ask_ink)); setHintTextColor(ink(R.color.ask_ink_3)); setPadding(dp(12), 0, dp(8), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { v, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submit(v.text.toString(), false); true } else false }
            // Typing needs the keyboard, so only then does the bar take focus from the app.
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { focusable(true); if (listening) ears?.cancel() }; false }
        }
        screenChip = text("", 13f, R.color.ask_ink_2).apply {
            tag = "rabbit-screen"; maxLines = 1; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0); isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(ink(R.color.ask_fill)) }
            setOnClickListener { screenOn = !screenOn; showScreenChip() }
        }
        model = icon(R.drawable.ic_surface, "Model", "rabbit-model") { context.startActivity(AssistantActivity.resume(context)) }
        mic = icon(R.drawable.ic_mic_line, "Talk to Ægentica. Tap to keep talking while it works, hold to say one thing.", "rabbit-mic", 20) { toggleConversation() }.apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { micDownAt = android.os.SystemClock.uptimeMillis(); false }
                    MotionEvent.ACTION_UP -> if (android.os.SystemClock.uptimeMillis() - micDownAt >= AssistantActivity.HOLD_MS && listening && !conversation) { ears?.stop(); v.isPressed = false; true } else false
                    else -> false
                }
            }
            setOnLongClickListener { if (!listening) listen(once = true); true }
        }
        send = icon(R.drawable.ic_arrow_up, "Send", "rabbit-send", 16) { submit(input.text.toString(), false) }.apply {
            imageTintList = ColorStateList.valueOf(ink(R.color.ask_on_ink))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink(R.color.ask_ink)) }
        }
        val plus = icon(R.drawable.ic_plus, "More: open in the sheet, Ægentica hands settings", "rabbit-plus") { context.startActivity(AssistantActivity.resume(context)); collapse() }
        bar = LinearLayout(context).apply {
            tag = "rabbit-bar"; orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = surface(24, translucent = true); elevation = dp(6).toFloat(); setPadding(dp(6), dp(6), dp(6), dp(4))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(barRabbit, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(screenChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(model, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(mic, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(send, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(2) })
            })
        }

        // The expanded card: the question, the steps, the answer, prompts and actions.
        question = text("", 13f, R.color.ask_ink_3).apply { tag = "rabbit-question"; maxLines = 2 }
        steps = LinearLayout(context).apply { tag = "rabbit-steps"; orientation = LinearLayout.VERTICAL }
        answer = text("", 15f).apply { tag = "rabbit-answer"; setTextIsSelectable(true) }
        extra = LinearLayout(context).apply { tag = "rabbit-extra"; orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        actions = LinearLayout(context).apply { tag = "rabbit-actions"; gravity = Gravity.CENTER_VERTICAL }
        jobs = JobCard(context, object : JobCard.Host {
            override fun tell(job: ServerJobs.Job, input: ServerJobs.Input?) = tellJob(job, input)
            override fun opened() = collapse()
        })
        val cardBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(8))
            addView(question); addView(steps); addView(answer); addView(extra)
            addView(jobs.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(actions)
        }
        card = LinearLayout(context).apply {
            tag = "rabbit-card"; orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = surface(20, translucent = false); elevation = dp(6).toFloat()
            addView(object : ScrollView(context) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec((context.resources.displayMetrics.heightPixels * 0.42f).toInt(), MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; addView(cardBody) })
        }

        val panelView = object : LinearLayout(context) {
            // A touch outside the genie while the hands work: the person took over the screen.
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { Hands.userTouched(); return false }
                return super.onTouchEvent(event)
            }
        }.apply {
            tag = "rabbit-panel"; orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val params = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(panelView, params)
        panel = panelView; panelParams = params
        place(Form.BAR)
    }

    private fun stopPill(tagName: String) = TextView(context).apply {
        tag = tagName; text = "Stop"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink(R.color.ask_on_ink))
        contentDescription = "Stop Ægentica now"; visibility = View.GONE
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(ink(R.color.ask_ink)) }
        setPadding(dp(14), 0, dp(14), 0); minWidth = dp(64); isClickable = true
        setOnClickListener { stopAll() }
    }

    private fun stopAll() {
        conversation = false; ears?.cancel(); listening = false; mouth?.hush()
        if (Hands.active || session?.running == true) Hands.stop() else session?.stop()
        session?.stop()
    }

    /** Width and place of the panel for a form: the bar spans the screen with a margin; the bubble keeps its spot. */
    private fun place(next: Form) {
        val params = panelParams ?: return
        val metrics = context.resources.displayMetrics
        val barWidth = minOf(metrics.widthPixels - dp(16), dp(560))
        params.width = if (next == Form.BUBBLE) ViewGroup.LayoutParams.WRAP_CONTENT else barWidth
        if (next != Form.BUBBLE) params.x = params.x.coerceIn(0, maxOf(0, metrics.widthPixels - barWidth))
        if (params.y == 0) params.y = dp(12)
        panel?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    /**
     * Drag the rabbit to move the genie; a tap without moving runs [tap], a hold says what it is
     * doing. Moving a window is the one thing here that is not a transform: Android has no other
     * way to move a window.
     */
    private fun draggable(view: View, tap: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        val hold = Runnable { if (!moved) { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); say() } }
        view.isClickable = true
        view.setOnTouchListener { v, e ->
            val params = panelParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; downAt = e.eventTime; main.postDelayed(hold, 500) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) { moved = true; main.removeCallbacks(hold) }
                    if (moved) {
                        val metrics = context.resources.displayMetrics
                        params.x = (startX + dx).toInt().coerceIn(0, maxOf(0, metrics.widthPixels - (panel?.width ?: 0)))
                        // Gravity is the bottom: dragging up raises y.
                        params.y = (startY - dy).toInt().coerceIn(0, maxOf(0, metrics.heightPixels - (panel?.height ?: 0) - dp(24)))
                        panel?.let { runCatching { wm.updateViewLayout(it, params) } }
                    }
                }
                MotionEvent.ACTION_UP -> { main.removeCallbacks(hold); if (!moved && e.eventTime - downAt < 500) { v.performClick(); tap() } }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(hold)
            }
            true
        }
    }

    private fun removeWindows() {
        listOfNotNull(stage, panel).forEach { runCatching { wm.removeViewImmediate(it) } }
        stage = null; panel = null; panelParams = null; away = false; form = Form.HIDDEN
        main.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- forms

    private fun ensure() {
        leaving?.let { main.removeCallbacks(it) }; leaving = null
        if (!showing) {
            addWindows(); ServerJobs.addObserver(jobWatcher); ServerJobs.active?.let { jobs.show(it) }
            lastActive = android.os.SystemClock.uptimeMillis()
            // Left alone, the rabbit loafs after a minute and sleeps after three.
            main.postDelayed(object : Runnable { override fun run() { if (!showing) return; if (!away) rest(); main.postDelayed(this, 30_000) } }, 30_000)
        }
        attach()
    }

    // ---------------------------------------------------------------- jobs on aegentica.se

    /** A job changed: the genie shows the one its conversation handed over, opens up when it asks, and reads a price aloud in a conversation. */
    private fun jobChanged(job: ServerJobs.Job) {
        if (jobs.job == null || (jobs.job?.finished == true && !job.finished)) jobs.show(job)
        if (jobs.job !== job) return
        if (telling?.first === job && telling?.second != null && job.inputs.none { it.requestId == telling?.second?.requestId }) endTelling()
        val input = job.inputs.firstOrNull()
        if (input != null && input.requestId != shownInput) {
            shownInput = input.requestId
            if (form != Form.EXPANDED) expand(content = true)
            rest()
            status.text = "Ægentica asks · " + input.prompt.oneLine().take(80)
            if (input.priced && input.requestId !in job.readBack && (conversation || session?.view()?.aloud == true)) {
                ears?.cancel(); listening = false; showMic()
                PriceVoice.ask(context, job, input, mouth ?: Mouth(context).also { mouth = it }, ears ?: Ears(context).also { ears = it }) { line -> if (showing) status.text = line }
            }
        } else if (input == null && session?.running != true && !Hands.active) {
            status.text = when {
                job.finished -> if (job.state == "completed") "The job on aegentica.se is done." else (job.error ?: "The job stopped.").take(120)
                else -> "On aegentica.se · " + (job.steps.lastOrNull { it.status == "running" }?.label ?: "Working")
            }
        }
    }

    /** The bar talks to the job now: its next words steer it, or answer its question. */
    private fun tellJob(job: ServerJobs.Job, question: ServerJobs.Input?) {
        telling = job to question
        input.hint = question?.let { "Answer: " + it.prompt.oneLine().take(50) } ?: "Tell the job…"
        focusable(true)
    }

    private fun endTelling() { telling = null; if (showing && !listening) input.hint = "Ask Ægentica" }

    /** Words for the job: an answer to its open question, else its newest instruction. */
    private fun sendToJob(words: String) {
        val (job, question) = telling ?: return
        endTelling()
        status.text = "Sending to the job…"
        ServerJobs.tell(context, job, words, question) { error -> if (showing) status.text = error ?: "Sent to the job." }
    }

    /**
     * The genie summoned with the assistant gesture: the bar opens over the app with the screen
     * Android shared, listening when the microphone is allowed.
     */
    override fun summon() = summon(listen = true)

    /**
     * The genie starts on a request the owner already made (an NFC tag, a door with words): it opens
     * over the app with the work showing at once, without listening first.
     */
    fun start(request: String, notes: List<String> = emptyList()) {
        summon(listen = false)
        screenPending = false; showScreenChip()
        val session = PhoneAgent.session(context, fresh = true)
        if (session !== this.session) { this.session?.removeObserver(observer); this.session = session; session.addObserver(observer) }
        settledFor = null
        PhoneAgent.ask(context, session, request, notes = notes)
        status.text = request.oneLine().take(120)
        expand(content = true)
    }

    private fun summon(listen: Boolean) {
        ensure()
        summoned = ScreenContexts.invocation
        screenPending = true; screenOn = true
        unlistenScreen?.invoke()
        unlistenScreen = ScreenContexts.listen { if (showing) showScreenChip() }
        showScreenChip()
        expand(content = session?.running == true || ServerJobs.active != null)
        if (!Hands.active && ServerJobs.active == null) status.text = "What should we do?"
        if (listen && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.getSharedPreferences("surfacelab", 0).getBoolean("assistant_listen", true)) main.postDelayed({ if (showing && !listening) listen(once = true) }, 250)
    }

    private fun showScreenChip() {
        if (!showing) return
        val c = ScreenContexts.current
        screenChip.visibility = if (screenPending && !c.empty) View.VISIBLE else View.GONE
        val app = Hands.screen?.appName(c.app)?.takeIf { it.isNotBlank() } ?: "Screen"
        screenChip.text = if (screenOn) app else "Screen off"
        screenChip.alpha = if (screenOn) 1f else 0.55f
        screenChip.contentDescription = if (screenOn) "Including $app. Tap to leave the screen out." else "The screen is left out. Tap to include it."
        model.setImageResource(if (PhoneAgent.ready(context)) Makers.logo(PhoneAgent.model(context)) ?: R.drawable.ic_surface else R.drawable.ic_surface)
    }

    fun expand(content: Boolean) {
        ensure()
        val from = form
        form = if (content) Form.EXPANDED else Form.BAR
        bubble.visibility = View.GONE
        bar.visibility = View.VISIBLE
        card.visibility = if (content) View.VISIBLE else View.GONE
        place(form)
        if (from == Form.BUBBLE || from == Form.HIDDEN) hero(bar, grow = true)
        session?.let { render(it) }
        lastActive = android.os.SystemClock.uptimeMillis(); rest()
    }

    fun collapse() {
        if (!showing) return
        focusable(false)
        form = Form.BUBBLE
        card.visibility = View.GONE; bar.visibility = View.GONE; bubble.visibility = View.VISIBLE
        place(form)
        hero(bubble, grow = true)
        rest()
    }

    /** The bar grows out of the rabbit, or the rabbit out of the bar: scale and fade, about 200 ms. */
    private fun hero(view: View, grow: Boolean) {
        if (!motion) { view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; return }
        view.pivotX = dp(28).toFloat(); view.pivotY = view.height.takeIf { it > 0 }?.toFloat() ?: dp(28).toFloat()
        view.alpha = 0f; view.scaleX = if (grow) 0.6f else 1.1f; view.scaleY = view.scaleX
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(HERO_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    /** The panel takes the keyboard only while the owner types; otherwise the app keeps it. */
    private fun focusable(on: Boolean) {
        val params = panelParams ?: return
        val flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == params.flags) return
        params.flags = flags
        panel?.let { runCatching { wm.updateViewLayout(it, params) } }
        if (on) main.postDelayed({ input.requestFocus(); context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0) }, 120)
        else context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------------------------------------------------------------- asking and voice

    /** Words from the box or the microphone: the newest instruction while it works, else a new question. */
    internal fun submit(raw: String, aloud: Boolean) {
        val words = raw.trim()
        if (words.isEmpty()) return
        input.setText("")
        focusable(false)
        lastActive = android.os.SystemClock.uptimeMillis()
        if (telling != null) { sendToJob(words); return }
        if (!PhoneAgent.ready(context)) { status.text = "Sign in to OpenRouter in Settings → Phone agent to use the agent."; return }
        val current = PhoneAgent.current?.takeIf { it.running }
        if (current != null) { status.text = "Got it: $words".take(120); Hands.steer(words); return }
        val fresh = screenPending
        val session = PhoneAgent.session(context, fresh = fresh).also { if (it !== session) { this.session?.removeObserver(observer); this.session = it; it.addObserver(observer) } }
        val screen = ScreenContexts.current.takeIf { screenOn && fresh }
        val app = screen?.app?.let { Hands.screen?.appName(it) }.orEmpty()
        settledFor = null
        PhoneAgent.ask(context, session, words, screen = screen, appName = app, aloud = aloud || conversation, firstOfInvocation = fresh)
        if (fresh) { screenPending = false; showScreenChip(); ScreenContexts.clear() }
        expand(content = true)
    }

    private fun toggleConversation() {
        if (conversation) { conversation = false; ears?.cancel(); listening = false; showMic(); return }
        conversation = true
        listen(once = false)
    }

    /** On-device dictation. In a conversation it reopens after each sentence; talking over the voice stops the voice. */
    private fun listen(once: Boolean) {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow the microphone for Ægentica in the app first."; conversation = false; return
        }
        val e = ears ?: Ears(context).also { ears = it }
        if (!e.available()) { status.text = "Offline speech is not ready on this phone. Type instead."; conversation = false; return }
        listening = true; showMic()
        input.hint = "Listening…"
        e.listen(
            onPartial = { heard -> input.setText(heard); if (heard.length > 2) mouth?.hush() },
            onFinal = { heard -> submit(heard, aloud = true) },
            onStop = { problem ->
                listening = false; input.hint = "Ask Ægentica"; showMic()
                if (problem != null) { status.text = problem.message; conversation = false }
                else if (conversation && showing && !once) main.postDelayed({ if (conversation && showing && !listening) listen(once = false) }, 250)
            })
    }

    private fun showMic() {
        if (!showing) return
        mic.imageTintList = ColorStateList.valueOf(ink(if (listening || conversation) R.color.ask_ink else R.color.ask_ink_3))
        mic.background = if (conversation) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink(R.color.ask_fill)) } else null
        mic.contentDescription = if (conversation) "Listening while it works. Tap to stop listening." else "Talk to Ægentica. Tap to keep talking while it works, hold to say one thing."
    }

    // ---------------------------------------------------------------- the agent's state

    private fun attach() {
        val current = PhoneAgent.current
        if (current === session) return
        session?.removeObserver(observer)
        session = current
        current?.addObserver(observer)
    }

    private fun render(session: AgentSession) {
        if (!showing) return
        val view = session.view()
        val busy = view.busy || Hands.active
        stop.visibility = if (busy) View.VISIBLE else View.GONE
        bubbleStop.visibility = stop.visibility
        question.text = view.question
        question.visibility = if (view.question.isBlank()) View.GONE else View.VISIBLE
        steps.removeAllViews()
        view.steps.takeLast(4).forEach { step ->
            val mark = when (step.status) { "done" -> "✓"; "error" -> "!"; "denied" -> "✕"; "awaiting-approval" -> "?"; else -> "•" }
            steps.addView(text("$mark  ${step.label}", 13f, if (step.status == "running") R.color.ask_ink_2 else R.color.ask_ink_3).apply { maxLines = 2; tag = "rabbit-step"; setPadding(0, dp(2), 0, dp(2)) })
        }
        if (view.answer.isNotBlank()) answer.text = Markdown.render(Prompts.reply(view.answer), dp(18), view.busy)
        answer.visibility = if (view.answer.isBlank()) View.GONE else View.VISIBLE
        val approval = view.approval
        when {
            approval != null -> showApproval(approval)
            mode == "approval" -> setMode("status")
        }
        val running = view.steps.lastOrNull { it.status == "running" }
        status.text = when {
            mode == "paused" || mode == "handover" -> status.text
            approval != null -> "Waiting for you · ${approval.label}"
            running != null -> running.label
            view.busy -> "Thinking…"
            view.error != null -> view.error
            view.answer.isNotBlank() -> "Done"
            else -> status.text
        }
        if (approval != null && form == Form.BUBBLE) expand(content = true)
        if (!away) rest()
        if (!view.busy && settledFor !== session && session.thread.isNotEmpty()) {
            settledFor = session
            showActions(view)
            if (Hands.active) Hands.end(view.answer.ifBlank { view.error })
            else if (view.aloud && view.answer.isNotBlank()) speak(view.answer)
        }
    }

    private fun showActions(view: AgentView) {
        actions.removeAllViews()
        if (view.answer.isBlank()) return
        val plain = Markdown.strip(Prompts.reply(view.answer))
        fun add(drawable: Int, description: String, tagName: String, run: () -> Unit) = actions.addView(icon(drawable, description, tagName) { run() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        add(R.drawable.ic_copy, "Copy", "rabbit-copy") { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ægentica", plain)); status.text = "Copied." }
        add(R.drawable.ic_volume, "Read aloud", "rabbit-read") { speak(view.answer) }
        add(R.drawable.ic_arrow_up_right, "Open in the sheet", "rabbit-open-sheet") { context.startActivity(AssistantActivity.resume(context)); collapse() }
        add(R.drawable.ic_close, "Close", "rabbit-dismiss") { hideSoon(0) }
    }

    private fun speak(markdown: String) {
        val text = Markdown.strip(Prompts.reply(markdown))
        if (text.isBlank()) return
        (mouth ?: Mouth(context).also { mouth = it }).apply { begin(Ears(context).locale()); finish(text) }
    }

    private fun setMode(next: String) {
        mode = next
        extra.removeAllViews()
        extra.visibility = if (next == "status") View.GONE else View.VISIBLE
        if (next != "approval") approvalShown = null
    }

    private fun button(title: String, tagName: String, primary: Boolean, run: () -> Unit) = TextView(context).apply {
        text = title; tag = tagName; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(14), 0, dp(14), 0); isClickable = true
        setTextColor(ink(if (primary) R.color.ask_on_ink else R.color.ask_ink))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(ink(if (primary) R.color.ask_ink else R.color.ask_fill)) }
        setOnClickListener { run() }
    }

    private fun buttons(vararg list: TextView) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            list.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(6); topMargin = dp(6) }) }
        })
    }

    /** An Allow prompt with its one-line why. Steps that always ask say why and offer no "always". */
    private fun showApproval(approval: Approval) {
        if (mode == "approval" && approvalShown == approval.call.id) return
        setMode("approval"); approvalShown = approval.call.id
        rest()
        extra.addView(text(approval.label, 15f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); setPadding(0, dp(8), 0, 0) })
        val detail = listOfNotNull(approval.reason, approval.appName.takeIf { it.isNotBlank() }?.let { "In $it" }).joinToString(" · ")
        if (detail.isNotBlank()) extra.addView(text(detail, 13f, R.color.ask_ink_3))
        val decide = { d: Decision -> setMode("status"); session?.decide(approval.call.id, d); rest() }
        val list = mutableListOf(button(if (approval.risk == Risk.ALWAYS) "Allow once" else "Allow", "rabbit-allow", true) { decide(Decision(true)) })
        if (approval.rememberable) list += button("Always in ${approval.appName}", "rabbit-always", false) { decide(Decision(true, always = true)) }
        list += button("Don't", "rabbit-deny", false) { decide(Decision(false)) }
        extra.addView(buttons(*list.toTypedArray()))
    }

    /** Hold the rabbit: it says what it is doing, in plain words. */
    private fun say() {
        val text = status.text.toString().ifBlank { "I am here. Tap me to ask something." }
        (mouth ?: Mouth(context).also { mouth = it }).apply { begin(Ears(context).locale()); finish(text) }
    }

    // ---------------------------------------------------------------- HandsOverlay

    override fun show() {
        ensure()
        if (form == Form.HIDDEN) expand(content = true)
        setMode("status")
        lastActive = android.os.SystemClock.uptimeMillis(); rest()
        stop.visibility = View.VISIBLE; bubbleStop.visibility = View.VISIBLE
    }

    private fun pose(p: Pose) {
        if (!showing) return
        barRabbit.setImageResource(p.drawable); bubbleRabbit.setImageResource(p.drawable)
        homePose = p
    }

    /** The pose at home, for tests and for holding the rabbit. */
    internal var homePose = Pose.SIT; private set
    private var lastDone = 0L
    private var lastActive = android.os.SystemClock.uptimeMillis()
    private var fidgeting: Runnable? = null
    private val random = java.util.Random()

    /**
     * The pose that fits what Ægentica is doing (Glimt's rules): begging while it waits for you,
     * looking while it works (turning while it thinks), a wave when it is done, standing while the
     * bar is open, and sitting, loafing and at last sleeping when left alone. Then a fidget now and then.
     */
    private fun rest() {
        if (!showing) return
        val view = session?.view()
        val now = android.os.SystemClock.uptimeMillis()
        val busy = view?.busy == true || Hands.active
        if (busy) lastActive = now
        val waiting = view?.approval != null || jobs.job?.inputs?.isNotEmpty() == true || mode == "handover"
        val p = if (mode == "paused") Pose.REST else RabbitMotion.restPose(
            waiting = waiting, thinking = busy && view?.steps?.none { it.status == "running" } != false, busy = busy,
            open = form == Form.BAR || form == Form.EXPANDED, typing = input.hasFocus() && input.text.isNotEmpty(),
            doneMs = if (lastDone == 0L) Long.MAX_VALUE else now - lastDone, idleMs = now - lastActive)
        pose(p)
        fidget(!busy && !waiting && mode == "status")
    }

    /** While it waits, the rabbit does a small thing on its own every 6 to 14 seconds; never with animations off. */
    private fun fidget(allowed: Boolean) {
        if (!allowed || !motion || away) { fidgeting?.let { main.removeCallbacks(it) }; fidgeting = null; return }
        if (fidgeting != null) return
        val go = object : Runnable {
            override fun run() {
                fidgeting = null
                if (!showing || away || !motion || mode != "status" || session?.running == true || Hands.active) return
                val f = RabbitMotion.fidget(random.nextDouble())
                val home = homeView()
                pose(f.pose)
                val shift = f.dx * RabbitMotion.SCALE * density * 0.5f
                home.animate().translationX(shift).setDuration(300).withEndAction {
                    home.animate().translationX(0f).setStartDelay(f.ms - 300).setDuration(300).withEndAction { if (showing) rest() }.start()
                }.start()
            }
        }
        fidgeting = go
        main.postDelayed(go, RabbitMotion.fidgetDelay(random.nextDouble()))
    }

    /** The rabbit's home on screen: the bar's rabbit, or the bubble. */
    private fun homeView(): View = if (form == Form.BUBBLE) bubbleRabbit else barRabbit

    private fun stageXY(x: Int, y: Int): Pair<Float, Float> { val o = IntArray(2); stage?.getLocationOnScreen(o); return (x - o[0]).toFloat() to (y - o[1]).toFloat() }

    /** Where the flying rabbit starts and ends: over its home, its feet on the home's bottom. */
    private fun homeSpot(p: Pose): Pair<Float, Float> {
        val home = homeView()
        val at = IntArray(2); home.getLocationOnScreen(at)
        val (x, y) = stageXY(at[0], at[1])
        return (x + (home.width - spriteW(p)) / 2f) to (y + home.height - spriteH(p).toFloat())
    }

    /**
     * Where the rabbit lands for [tool] on [target] (Glimt's rule: beside it on the left, feet on
     * its bottom edge, or on its right without room; always on screen): the sprite's top-left, and
     * whether it is mirrored to face the target.
     */
    internal fun landing(target: Rect, screenW: Int, screenH: Int, tool: String = ""): Triple<Float, Float, Boolean> {
        val p = RabbitMotion.poseFor(tool)
        val spot = RabbitMotion.landing(target, spriteW(p), spriteH(p), screenW, screenH, p, gap = dp(6), margin = dp(8))
        return Triple(spot.x.toFloat(), (spot.y - spriteH(p)).toFloat(), spot.flip)
    }

    private fun show(view: ImageView, p: Pose, flip: Boolean) {
        view.setImageResource(p.drawable)
        view.layoutParams = view.layoutParams.apply { width = spriteW(p); height = spriteH(p) }
        view.scaleX = if (flip) -1f else 1f
    }

    /** A trip from where the flyer is to ([tx], [ty]): hops for a short way, a leap across the screen. */
    private fun travel(tx: Float, ty: Float, then: () -> Unit) {
        val sx = flyer.translationX; val sy = flyer.translationY
        val distance = Math.hypot((tx - sx).toDouble(), (ty - sy).toDouble()).toFloat() / density
        val ms = RabbitMotion.travelMs(distance, reduced = !motion)
        if (ms == 0L) { flyer.translationX = tx; flyer.translationY = ty; then(); return }
        val p = RabbitMotion.travelPose(distance)
        show(flyer, p, RabbitMotion.flipFor(p, faceRight = tx >= sx))
        val hops = RabbitMotion.hops(distance)
        val height = dp(if (p == Pose.LEAP) 48 else 22).toFloat()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                flyer.translationX = sx + (tx - sx) * t
                flyer.translationY = sy + (ty - sy) * t - height * abs(Math.sin(Math.PI * hops * t)).toFloat()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { then() } })
            start()
        }
    }

    override fun flyTo(bounds: Rect, tool: String, then: () -> Unit) {
        if (!showing || !motion) { if (showing) highlight(bounds); then(); return }
        returning?.let { main.removeCallbacks(it) }
        fidget(false)
        val origin = IntArray(2); stage?.getLocationOnScreen(origin)
        val local = Rect(bounds).apply { offset(-origin[0], -origin[1]) }
        val metrics = context.resources.displayMetrics
        val (tx, ty, flip) = landing(local, metrics.widthPixels, metrics.heightPixels, tool)
        if (!away) {
            val (hx, hy) = homeSpot(Pose.HOP)
            flyer.translationX = hx; flyer.translationY = hy
            flyer.visibility = View.VISIBLE; homeView().alpha = 0f
            away = true
        }
        travel(tx, ty) {
            // Landed: it acts out the step, facing the target.
            show(flyer, RabbitMotion.poseFor(tool), flip)
            then(); scheduleReturn()
        }
    }

    /** After its last step the rabbit waits a moment, then hops home. */
    private fun scheduleReturn() {
        returning?.let { main.removeCallbacks(it) }
        val back = Runnable {
            if (!showing || !away) return@Runnable
            val (hx, hy) = homeSpot(Pose.HOP)
            travel(hx, hy) { flyer.visibility = View.INVISIBLE; homeView().alpha = 1f; away = false; rest() }
        }
        returning = back
        main.postDelayed(back, LINGER_MS)
    }

    private fun effect(view: View, x: Float, y: Float, w: Int, h: Int, animate: (View) -> android.view.ViewPropertyAnimator) {
        val s = stage ?: return
        s.addView(view, FrameLayout.LayoutParams(w, h))
        view.translationX = x; view.translationY = y
        animate(view).withEndAction { s.removeView(view) }.start()
    }

    private fun sparkles(cx: Float, cy: Float, count: Int) {
        repeat(count) { i ->
            val angle = Math.PI * 2 * i / count + 0.4
            val d = dp(22)
            val star = ImageView(context).apply { setImageResource(R.drawable.ic_sparkle); scaleX = 0.2f; scaleY = 0.2f; alpha = 0f }
            effect(star, cx + (Math.cos(angle) * d).toFloat() - dp(6), cy + (Math.sin(angle) * d).toFloat() - dp(6), dp(12), dp(12)) {
                it.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(45f).setDuration(220).withEndAction { it.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(240).start() }
            }
        }
    }

    override fun tapped(x: Int, y: Int) {
        if (!showing || !motion) return
        val (cx, cy) = stageXY(x, y)
        val start = dp(20)
        val ripple = View(context).apply { background = ring(GradientDrawable.OVAL); tag = "rabbit-ripple" }
        effect(ripple, cx - start / 2f, cy - start / 2f, start, start) { it.animate().scaleX(3.2f).scaleY(3.2f).alpha(0f).setDuration(450) }
        sparkles(cx, cy, 3)
        if (away) flyer.animate().translationYBy(-dp(6).toFloat()).setDuration(90).withEndAction { flyer.animate().translationYBy(dp(6).toFloat()).setDuration(120).start() }.start()
    }

    override fun typing(bounds: Rect) {
        if (!showing || !motion || !away) return
        show(flyer, Pose.POUNCE, flyer.scaleX < 0)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener { flyer.rotation = (Math.sin((it.animatedValue as Float) * Math.PI * 6) * 6).toFloat() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { flyer.rotation = 0f } })
            start()
        }
    }

    override fun scrolled(bounds: Rect, direction: String) {
        if (!showing || !motion || !away) return
        val shift = dp(8).toFloat() * if (direction == "up") 1 else -1
        flyer.animate().translationYBy(shift).setDuration(160).withEndAction { flyer.animate().translationYBy(-shift).setDuration(200).start() }.start()
    }

    override fun highlight(bounds: Rect) {
        if (!showing) return
        val o = IntArray(2); stage?.getLocationOnScreen(o)
        val pad = dp(4)
        val box = View(context).apply { background = ring(GradientDrawable.RECTANGLE, dp(10).toFloat()); alpha = 0f; tag = "rabbit-ring" }
        effect(box, (bounds.left - o[0] - pad).toFloat(), (bounds.top - o[1] - pad).toFloat(), bounds.width() + pad * 2, bounds.height() + pad * 2) {
            it.animate().alpha(1f).setDuration(if (motion) 150 else 0).withEndAction { it.animate().alpha(0f).setStartDelay(1200).setDuration(if (motion) 300 else 0).start() }
        }
    }

    override fun poof() {
        if (!showing || !motion) return
        val target = if (away) flyer else homeView()
        val flipped = target === flyer && flyer.scaleX < 0
        target.animate().scaleX(if (flipped) -1.18f else 1.18f).scaleY(1.18f).alpha(0.6f).setDuration(140).withEndAction {
            target.animate().scaleX(if (flipped) -1f else 1f).scaleY(1f).alpha(if (target === flyer || !away) 1f else 0f).setDuration(180).start()
        }.start()
        val at = IntArray(2); target.getLocationOnScreen(at)
        val (cx, cy) = stageXY(at[0] + target.width / 2, at[1] + target.height / 2)
        sparkles(cx, cy, 4)
    }

    override fun status(text: String) { if (showing) status.text = text }

    override fun handOver(text: String) {
        show()
        expand(content = true)
        setMode("handover")
        rest()
        status.text = "$text, then tap Continue."
        extra.addView(buttons(button("Continue", "rabbit-continue", true) { setMode("status"); rest(); Hands.handBack() }))
    }

    override fun paused() {
        if (!showing) return
        expand(content = true)
        setMode("paused")
        rest()
        status.text = "You have the screen. Tap Continue when you are done."
        extra.addView(buttons(button("Continue", "rabbit-continue", true) { setMode("status"); rest(); Hands.handBack() }))
    }

    override fun stopped() {
        if (!showing) return
        setMode("status")
        rest()
        status.text = "Stopped."
        stop.visibility = View.GONE; bubbleStop.visibility = View.GONE
        conversation = false; ears?.cancel(); listening = false; showMic()
    }

    override fun done(answer: String?) {
        if (!showing) return
        setMode("status")
        lastDone = android.os.SystemClock.uptimeMillis(); rest()
        stop.visibility = View.GONE; bubbleStop.visibility = View.GONE
        status.text = Markdown.strip(answer.orEmpty()).replace(Regex("\\s+"), " ").trim().ifBlank { "Done." }.take(120)
        if (form == Form.BUBBLE) expand(content = true)
        // A spoken question gets its answer read aloud, as in the sheet.
        val view = session?.view()
        if (view?.aloud == true && !answer.isNullOrBlank()) speak(answer)
        main.postDelayed({ if (showing) rest() }, 2300)
    }

    /** Fade away; the gesture or the bubble brings it back. */
    fun hideSoon(ms: Long) {
        leaving?.let { main.removeCallbacks(it) }
        val go = Runnable {
            if (Hands.active || session?.running == true) { collapse(); return@Runnable }
            val p = panel
            if (p != null && motion) p.animate().alpha(0f).setDuration(HERO_MS).withEndAction { close() }.start() else close()
        }
        leaving = go
        main.postDelayed(go, ms)
    }

    override fun hideForCapture(hidden: Boolean) {
        val state = if (hidden) View.INVISIBLE else View.VISIBLE
        stage?.visibility = state; panel?.visibility = state
    }

    override fun close() {
        PriceVoice.cancel(ears)
        conversation = false; ears?.cancel(); listening = false; mouth?.close(); mouth = null
        ServerJobs.removeObserver(jobWatcher); if (::jobs.isInitialized) jobs.detach(); telling = null; shownInput = null
        // The conversation goes to Ægentica's chat history once it is over (when the phone is connected).
        session?.takeIf { !it.running && !Hands.active }?.let { Backend.saveConversation(context, it) }
        unlistenScreen?.invoke(); unlistenScreen = null
        if (summoned >= 0) { ScreenContexts.clear(summoned); summoned = -1 }
        session?.removeObserver(observer); session = null
        removeWindows()
    }

    companion object {
        /** The longest trip to a target (Glimt's rule: 240 to 620 ms by distance). */
        const val FLIGHT_MS = 620L
        /** How long it stays at the target after a step before hopping home. */
        const val LINGER_MS = 1600L
        /** Bar, bubble and card change into each other in this time. */
        const val HERO_MS = 200L
    }
}
