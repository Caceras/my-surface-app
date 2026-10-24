package com.caceras.surfacelab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Ægentica's browser on aegentica.se, full screen, live: watch the agent work, then take over to
 * tap, scroll and type yourself (a password or a code the agent must never type), and hand it
 * back. Taps and scrolls go as fractions of the page, through aegentica.se with this phone's
 * access token. Nothing in the picture is read or run on the phone.
 */
class LiveViewActivity : Activity() {
    private lateinit var browser: LiveBrowser
    private lateinit var image: LiveImage
    private lateinit var status: TextView
    private lateinit var control: TextView
    private lateinit var typing: LinearLayout
    private lateinit var input: EditText
    private var downY = 0f
    private var downX = 0f
    private var moved = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        browser = LiveBrowser(this)
        val canvas = ink(R.color.ask_surface)
        status = label("Connecting to Ægentica's browser…", 13f, true).apply { tag = "live-status"; maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        image = LiveImage(this).apply { tag = "live-picture"; contentDescription = "Ægentica's browser, live" }
        image.setOnTouchListener { _, e -> touch(e); true }
        control = pill("Take over", primary = true) { toggle() }.apply { tag = "live-control" }
        val back = pill("Back") { browser.back(::problem) }.apply { tag = "live-back" }
        val keyboard = pill("Type") { typing.visibility = if (typing.visibility == View.VISIBLE) View.GONE else View.VISIBLE; if (typing.visibility == View.VISIBLE) input.requestFocus() }.apply { tag = "live-type" }
        val close = pill("Close") { finish() }.apply { tag = "live-close" }
        input = EditText(this).apply {
            tag = "live-input"; hint = "Type into the page"; isSingleLine = true; textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        typing = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; padDp(12, 0, 12, 0)
            addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(pill("Send") { sendTyped() }.apply { tag = "live-send" })
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; padDp(12, 8, 12, 8)
            listOf(control, back, keyboard, close).forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(canvas)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), dp(8)) })
            addView(FrameLayout(this@LiveViewActivity).apply { padDp(8, 0, 8, 0); addView(image, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(typing, LinearLayout.LayoutParams(-1, -2))
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            padForSystemBars()
        }
        setContentView(root)
        readableSystemBars()
        browser.listener = { render() }
        browser.start()
        render()
    }

    override fun onDestroy() {
        browser.listener = null
        browser.stop()
        super.onDestroy()
    }

    private fun render() {
        image.show(browser)
        val s = browser.status
        control.text = if (browser.human) "Hand back" else "Take over"
        control.contentDescription = if (browser.human) "Hand the browser back to Ægentica" else "Take over the browser yourself; Ægentica waits"
        status.text = when {
            browser.problem != null -> browser.problem
            !browser.connected && browser.frame == null -> "Connecting to Ægentica's browser…"
            browser.handOver -> "Ægentica asks you to take over: type your password or code yourself, then hand it back."
            browser.human -> "You have the browser. Tap, scroll and type, then tap Hand back."
            else -> listOfNotNull(browser.action?.label, s?.title ?: s?.url).joinToString(" · ").ifBlank { "Watching Ægentica work" }
        }
    }

    private fun problem(error: String?) { if (error != null) status.text = error }

    private fun toggle() {
        if (browser.human) browser.handBack(::problem) else browser.takeOver(::problem)
    }

    /** Touches reach the page only after taking over: a tap is a tap there, a drag scrolls it. */
    private fun touch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.y - downY) > dp(12) || kotlin.math.abs(e.x - downX) > dp(12)) moved = true
            MotionEvent.ACTION_UP -> {
                if (!browser.human) { status.text = "Tap Take over to use the browser yourself."; return }
                val at = image.toPage(downX, downY) ?: return
                if (moved) {
                    // Drag up to scroll down, as on the phone; in the page's own pixels.
                    val values = FloatArray(9); image.imageMatrix.getValues(values)
                    val dy = (downY - e.y) / values[android.graphics.Matrix.MSCALE_Y].coerceAtLeast(0.01f)
                    browser.scroll(at.first, at.second, dy, ::problem)
                } else browser.tap(at.first, at.second, ::problem)
            }
        }
    }

    private fun sendTyped() {
        val text = input.text.toString()
        if (text.isEmpty()) { browser.key("Enter", ::problem); return }
        if (!browser.human) { status.text = "Tap Take over first, so Ægentica waits while you type."; return }
        browser.type(text, ::problem)
        input.setText("")
    }

    companion object {
        fun intent(context: Context) = Intent(context, LiveViewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
