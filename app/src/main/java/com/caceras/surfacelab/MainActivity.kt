package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A chat window against a model running on this phone.
 *
 * Everything the app can do lives behind one conversation: type or speak a
 * question, watch the answer arrive, hear it back if you asked out loud. The
 * other five surfaces still exist and still work; they are simply not what
 * this screen is about, so they sit behind "More" instead of filling it.
 *
 * Every dimension goes through dp(): setPadding takes pixels, and raw
 * numbers make the whole screen shrink as density rises.
 */
class MainActivity : Activity() {

    private lateinit var messages: LinearLayout
    private lateinit var transcript: ScrollView
    private lateinit var input: EditText
    private lateinit var openers: HorizontalScrollView
    private lateinit var send: ImageButton
    private lateinit var status: TextView
    private var mic: ImageButton? = null

    private val ears by lazy { Ears(this) }
    private var mouth: Mouth? = null

    /** True while the pending question came from the microphone. */
    private var askedAloud = false
    private var listening = false
    private var busy = false

    /**
     * Set on the way out, and checked by every brain callback.
     *
     * SurfaceBrain.run takes no cancellation token, so its callbacks arrive
     * later whether or not this screen is still here -- landing text on a
     * dead view and saving an answer nobody is waiting for.
     */
    private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val brain = Brains.get()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.chat_bg))
        }

        root.addView(header(brain), wide())
        root.addView(buildTranscript(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buildOpeners(), wide())
        root.addView(composer(), wide())

        setContentView(root)
        root.padForSystemBars()

        brain.status(this) { status.text = it.label }

        // Anything shared into the app becomes the next thing you send,
        // rather than a read-only block of text to look at.
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
            if (shared.isNotEmpty()) {
                input.setText(shared)
                input.setSelection(shared.length)
            }
        }
    }

    // ------------------------------------------------------------- chrome

    private fun header(brain: SurfaceBrain): View {
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(color(R.color.text_primary))
        }

        status = TextView(this).apply {
            text = getString(R.string.working)
            textSize = 13f
            setTextColor(color(R.color.text_dim))
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status)
        }

        val more = TextView(this).apply {
            text = getString(R.string.more)
            textSize = 14f
            setTextColor(color(R.color.text_dim))
            padDp(12, 8, 4, 8)
        }

        val panel = morePanel(brain).apply { visibility = View.GONE }
        more.setOnClickListener {
            panel.visibility =
                if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titles, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(more)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 14, 20, 6)
            addView(row, wide())
            addView(panel, wide())
        }
    }

    /**
     * The template's own reference material: what this app registered with
     * the system, and the two buttons that act on it. Useful, and not what
     * you came to this screen for.
     */
    private fun morePanel(brain: SurfaceBrain): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(0, 10, 0, 4)
        }

        fun line(title: String, hint: String) {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(color(R.color.text_primary))
                padDp(0, 8, 0, 0)
            })
            panel.addView(TextView(this).apply {
                text = hint
                textSize = 13f
                setTextColor(color(R.color.text_dim))
            })
        }

        panel.addView(TextView(this).apply {
            text = getString(R.string.build_label, buildLabel())
            textSize = 13f
            setTextColor(color(R.color.text_dim))
        })

        panel.addView(flatButton(getString(R.string.prepare_model)) {
            status.text = getString(R.string.working)
            brain.prepare(this) { status.text = it.label }
        })

        line("Text selection", "Select text anywhere: " +
            brain.tasks.joinToString(", ") { it.alias })
        line("Quick Settings tile", "Shade, Edit tiles, or the button below.")
        line("Home screen widget", "Long-press home, Widgets. Shows the last answer.")
        line("App shortcuts", "Long-press the app icon in the launcher.")
        line("Share sheet", "Share any text into this app.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            panel.addView(flatButton(getString(R.string.add_tile)) {
                getSystemService(StatusBarManager::class.java)
                    .requestAddTileService(
                        ComponentName(this, SurfaceTileService::class.java),
                        getString(R.string.app_name),
                        Icon.createWithResource(this, R.drawable.ic_surface),
                        mainExecutor
                    ) { /* result code, ignored for a prototype */ }
            })
        }

        return panel
    }

    // ---------------------------------------------------------- the chat

    private fun buildTranscript(): View {
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(14, 6, 14, 6)
        }
        transcript = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(messages, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            // Tapping the conversation stops the answer being read aloud.
            // Not being able to shut it up is what makes a talking app feel
            // like an appliance.
            setOnClickListener { mouth?.hush() }
        }
        return transcript
    }

    private fun buildOpeners(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            padDp(14, 0, 14, 8)
        }
        Prompts.OPENERS.forEach { opener ->
            row.addView(chip(opener) {
                input.setText("$opener ")
                input.setSelection(input.text.length)
                input.requestFocus()
            })
        }
        openers = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        return openers
    }

    private fun composer(): View {
        input = EditText(this).apply {
            hint = getString(R.string.chat_hint)
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_dim))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            padDp(16, 12, 8, 12)
        }

        send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = getDrawable(R.drawable.send_bg)
            contentDescription = getString(R.string.send)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                // Modality is inherited: this answer is spoken only because
                // the question was. The flag is consumed here, so the next
                // typed question comes back quiet again.
                val aloud = askedAloud
                askedAloud = false
                ask(input.text.toString(), aloud)
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = getDrawable(R.drawable.composer_bg)
            addView(input, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // No on-device recogniser means no microphone at all, rather than a
        // button that quietly sends audio somewhere. See docs/voice.md.
        if (ears.available()) {
            val button = ImageButton(this).apply {
                setImageResource(R.drawable.ic_mic)
                background = null
                contentDescription = getString(R.string.mic)
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { toggleListening() }
            }
            mic = button
            bar.addView(button)
        }
        bar.addView(send)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(12, 0, 12, 12)
            addView(bar, wide())
        }
    }

    // ------------------------------------------------------------ asking

    private fun ask(question: String, aloud: Boolean) {
        val text = question.trim()
        if (text.isEmpty() || busy) return

        busy = true
        input.setText("")
        openers.visibility = View.GONE
        mouth?.hush()

        addBubble(text, fromUser = true)
        val answer = addBubble(getString(R.string.working), fromUser = false)

        if (aloud) speaker().begin(ears.locale())

        val brain = Brains.get()
        brain.run(
            context = this,
            task = Task.ASK,
            input = "",
            instruction = text,
            onPartial = { partial ->
                if (gone) return@run
                answer.text = Markdown.render(partial)
                if (aloud) mouth?.follow(Markdown.strip(partial))
                scrollToEnd()
            }
        ) { result ->
            if (gone) return@run
            busy = false
            val note = if (result.ok) Lang.caveat(Task.ASK, text) else null
            answer.text = when {
                result.ok && note == null -> result.text
                result.ok -> result.text + "\n\n" + note
                else -> result.note ?: getString(R.string.failed)
            }
            if (result.ok) {
                ResultStore.save(this, Task.ASK, result.text)
                if (aloud) mouth?.finish(Markdown.strip(result.text))
            }
            scrollToEnd()
        }
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            background = getDrawable(
                if (fromUser) R.drawable.bubble_you else R.drawable.bubble_ai
            )
            setTextColor(color(
                if (fromUser) R.color.bubble_you_text else R.color.bubble_ai_text
            ))
            padDp(16, 12, 16, 12)
            // Tapping an answer stops it being spoken.
            if (!fromUser) setOnClickListener { mouth?.hush() }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(6)
            bottomMargin = dp(6)
            // A bubble that runs the full width stops reading as a bubble.
            if (fromUser) leftMargin = dp(48) else rightMargin = dp(48)
        }

        messages.addView(bubble, params)
        scrollToEnd()
        return bubble
    }

    private fun scrollToEnd() =
        transcript.post { transcript.fullScroll(ScrollView.FOCUS_DOWN) }

    // ----------------------------------------------------------- speaking

    private fun speaker(): Mouth = mouth ?: Mouth(this).also { mouth = it }

    private fun toggleListening() {
        if (listening) {
            ears.stop()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        startListening()
    }

    private fun startListening() {
        mouth?.hush()
        listening = true
        setMicActive(true)
        input.hint = getString(R.string.listening)

        ears.listen(
            onLevel = { level(it) },
            onPartial = { partial ->
                // Words appear as they are recognised, so the screen is
                // never blank while you are talking.
                input.setText(partial)
                input.setSelection(input.text.length)
            },
            onFinal = { text ->
                input.setText(text)
                input.setSelection(input.text.length)
                // The transcript stays editable: a misheard word is a fix,
                // not a redo. Send speaks the answer back, because this
                // question was asked out loud.
                askedAloud = true
            },
            onStop = { problem ->
                listening = false
                setMicActive(false)
                input.hint = getString(R.string.chat_hint)
                if (problem != null) report(problem)
            }
        )
    }

    /**
     * The status line doubles as the voice status line -- there is only one,
     * and both answer the same question. The missing-language case is the
     * only speech failure with something to do about it, so it is the only
     * one that leaves something to tap.
     */
    private fun report(problem: VoiceProblem) {
        status.text = problem.message
        if (!problem.languageMissing || !ears.canFetchLanguage()) {
            status.setOnClickListener(null)
            return
        }
        status.text = problem.message + " " + getString(R.string.get_offline_speech)
        status.setOnClickListener {
            status.setOnClickListener(null)
            status.text = getString(R.string.working)
            ears.fetchLanguage { outcome -> if (!gone) status.text = outcome }
        }
    }

    private fun setMicActive(active: Boolean) {
        mic?.setColorFilter(
            color(if (active) R.color.listening else R.color.text_dim)
        )
        if (!active) level(0f)
    }

    /**
     * The microphone button is the level meter. rmsdB runs from roughly -2 to
     * 10, and only the loud half of that is worth showing -- the point is
     * that the screen is never blank while you are talking.
     */
    private fun level(rms: Float) {
        val scale = 1f + (rms.coerceIn(0f, 10f) / 20f)
        mic?.scaleX = scale
        mic?.scaleY = scale
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            status.text = getString(R.string.mic_denied)
        }
    }

    override fun onPause() {
        super.onPause()
        // The microphone is not held across a trip to another app, and the
        // phone does not keep talking to an empty room.
        ears.cancel()
        listening = false
        setMicActive(false)
        mouth?.hush()
    }

    override fun onDestroy() {
        gone = true
        ears.cancel()
        mouth?.close()
        mouth = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ helpers

    /**
     * Added without this, a child of a vertical LinearLayout defaults to
     * WRAP_CONTENT width -- so the header would not span the screen and the
     * "More" button would sit next to the title instead of opposite it.
     */
    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun color(id: Int) = resources.getColor(id, theme)

    /**
     * The version this APK was built from. On screen because the loop is
     * change it, push it, install it, look -- and that last step needs
     * something to look at.
     */
    private fun buildLabel(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    private fun flatButton(text: String, onTap: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color(R.color.accent))
            padDp(0, 12, 0, 4)
            setOnClickListener { onTap() }
        }

    private fun chip(text: String, onTap: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color(R.color.text_primary))
            background = getDrawable(R.drawable.chip_bg)
            padDp(14, 8, 14, 8)
            setOnClickListener { onTap() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.rightMargin = dp(8)
            layoutParams = params
        }

    private companion object {
        const val MIC_REQUEST = 1
    }
}
