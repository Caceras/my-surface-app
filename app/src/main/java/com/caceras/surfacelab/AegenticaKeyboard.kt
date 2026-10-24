package com.caceras.surfacelab

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Ægentica keys: an input method that sits beside Gboard. Switch to it with
 * the keyboard switcher to fix, improve, shorten, translate or continue what
 * you are typing, answer copied text, or dictate, then switch back with one
 * tap. The field is read only when an action is tapped, never in password
 * fields, and nothing is written until Replace or Insert is tapped.
 */
class AegenticaKeyboard : InputMethodService() {
    private var panel: KeyboardPanel? = null

    override fun onCreateInputView(): View = KeyboardPanel(this, object : KeyboardPanel.Host {
        override fun connection() = currentInputConnection
        override fun editor() = currentInputEditorInfo
        override fun backToKeyboard() {
            if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }).also { panel = it }.view

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panel?.reset()
    }

    /** The field closed or the keyboard was hidden: stop listening and answering. */
    override fun onFinishInputView(finishingInput: Boolean) {
        panel?.stop()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() { panel?.stop(); panel = null; super.onDestroy() }
}

/** The keyboard's panel, separate from the service so it can be tested with any text field. */
class KeyboardPanel(private val context: Context, private val host: Host) {
    interface Host {
        fun connection(): InputConnection?
        fun editor(): EditorInfo?
        fun backToKeyboard()
    }

    private val title: TextView
    private val preview: TextView
    private val status: TextView
    private val chips: LinearLayout
    private val results: LinearLayout
    private val mic: ImageButton
    private var brain: SurfaceBrain? = null
    private var ears: Ears? = null
    private var request = 0
    private var source: KeyboardText.Source? = null
    private var result = ""
    val view: View

    private fun ink(id: Int) = context.getColor(id)
    private fun press() = ColorStateList.valueOf(ink(R.color.ask_press))
    private fun pill(fill: Int?) = GradientDrawable().apply { fill?.let { setColor(ink(it)) }; cornerRadius = context.dp(16).toFloat() }

    private fun chip(text: String, tagName: String, primary: Boolean = false, run: () -> Unit) = context.label(text, 13.5f).apply {
        tag = tagName; gravity = Gravity.CENTER_VERTICAL; maxLines = 1; setLineSpacing(0f, 1f); isFocusable = true; buttonSemantics()
        setTextColor(ink(if (primary) R.color.ask_on_ink else R.color.ask_ink_2))
        background = InsetDrawable(RippleDrawable(press(), pill(if (primary) R.color.ask_ink else R.color.ask_fill), null), 0, context.dp(6), 0, context.dp(6))
        setPadding(context.dp(14), 0, context.dp(14), 0)
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); run() }
    }

    private fun icon(drawable: Int, description: String, tagName: String, run: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(ink(R.color.ask_ink_2)); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description; tag = tagName; background = RippleDrawable(press(), null, null)
        val pad = context.dp(11); setPadding(pad, pad, pad, pad)
        setOnClickListener { run() }
    }

    init {
        title = context.label("Ægentica", 14f).apply { medium(); setTextColor(ink(R.color.ask_ink)) }
        mic = icon(R.drawable.ic_mic_line, "Dictate", "keys-mic") { dictate() }
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_keyboard, "Back to your keyboard", "keys-back") { stop(); host.backToKeyboard() }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.dp(4) })
            addView(mic, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
        }
        preview = context.label("", 15f).apply { tag = "keys-preview"; setTextColor(ink(R.color.ask_ink)); setLineSpacing(0f, 1.25f); setTextIsSelectable(false) }
        status = context.label("", 12f).apply { tag = "keys-status"; setTextColor(ink(R.color.ask_ink_3)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        chips = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "keys-actions" }
        results = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "keys-results"; visibility = View.GONE }
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; tag = "keys-panel"; padDp(8, 6, 8, 10)
            background = GradientDrawable().apply {
                setColor(ink(R.color.ask_keys))
                val r = context.dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(context).apply { addView(preview); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, context.dp(96)).apply { marginStart = context.dp(8); marginEnd = context.dp(8) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { marginStart = context.dp(8); topMargin = context.dp(2) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(results) }, LinearLayout.LayoutParams(-1, -2))
        }
        ACTIONS.forEach { (label, instruction) -> chips.addView(chip(label, "keys-action") { act(label, instruction) }, LinearLayout.LayoutParams(-2, context.dp(44)).apply { marginEnd = context.dp(6) }) }
        chips.addView(chip("Reply to copied", "keys-reply") { reply() }, LinearLayout.LayoutParams(-2, context.dp(44)))
        reset()
    }

    private fun locked() = KeyboardText.secret(host.editor())

    /** A new field: forget the previous text and answer. */
    fun reset() {
        stop()
        source = null; result = ""; results.visibility = View.GONE; results.removeAllViews()
        if (locked()) {
            preview.text = ""; status.text = "Ægentica is off in password fields."
            chips.alpha = 0.4f; mic.isEnabled = false
        } else {
            preview.text = ""; status.text = "Choose what to do with the text in this field, or dictate."
            chips.alpha = 1f; mic.isEnabled = true
        }
    }

    fun stop() {
        request++
        brain?.cancel(); brain = null
        ears?.cancel(); mic.setColorFilter(null)
    }

    private fun model(): SurfaceBrain = ConnectedAI.assistantBrain(context)
    private fun modelLabel() = if (ConnectedAI.forVoice(context)) ConnectedAI.label(context) else "Gemini Nano"

    private fun act(label: String, instruction: String) {
        if (locked()) return
        val connection = host.connection()
        if (connection == null) { status.text = "No text field is active."; return }
        val read = KeyboardText.read(connection)
        if (read == null) { status.text = "Type or select some text first."; return }
        ask(label, instruction, read)
    }

    /** Answer the message on the clipboard; the field's text, if any, says how. */
    private fun reply() {
        if (locked()) return
        val copied = context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (copied.isEmpty()) { status.text = "Copy the message you want to answer first."; return }
        val wish = host.connection()?.let { KeyboardText.read(it)?.text }.orEmpty().trim()
        val instruction = "Write a reply to this message, ready to send, in the message's language." +
            if (wish.isNotEmpty()) " The reply should say: $wish" else ""
        ask("Reply", instruction, KeyboardText.Source(copied.take(KeyboardText.MAX), selected = false, field = false))
    }

    private fun ask(label: String, instruction: String, read: KeyboardText.Source) {
        stop()
        val chosen = model()
        brain = chosen
        source = read
        val token = ++request
        result = ""; preview.text = ""; results.visibility = View.GONE; results.removeAllViews()
        status.text = "$label · ${modelLabel()}"
        chosen.run(context, Task.KEYBOARD, read.text, instruction, { partial ->
            if (token == request) { result = Prompts.reply(partial).trim(); preview.text = result }
        }, { r ->
            if (token == request) {
                brain = null
                if (r.ok && r.text.isNotBlank()) { result = Prompts.reply(r.text).trim(); preview.text = result; status.text = "$label · ${modelLabel()}"; offer() }
                else status.text = r.note ?: "No answer. Try again."
            }
        })
    }

    /** Replace, Insert and Copy: nothing reaches the field until one is tapped. */
    private fun offer() {
        results.removeAllViews(); results.visibility = View.VISIBLE
        fun add(view: View) = results.addView(view, LinearLayout.LayoutParams(-2, context.dp(44)).apply { marginEnd = context.dp(6) })
        val read = source
        if (read != null && read.field) add(chip(if (read.selected) "Replace selection" else "Replace text", "keys-replace", primary = true) {
            host.connection()?.let { KeyboardText.replace(it, read, result); status.text = "Replaced." }
            results.visibility = View.GONE
        })
        add(chip("Insert", "keys-insert", primary = read?.field != true) {
            host.connection()?.commitText(result, 1); status.text = "Inserted."; results.visibility = View.GONE
        })
        add(chip("Copy", "keys-copy") {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Ægentica", result))
            status.text = "Copied."
        })
    }

    /** On-device dictation straight into the field. */
    private fun dictate() {
        if (locked()) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow the microphone for Ægentica, then try again."
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val e = ears ?: Ears(context).also { ears = it }
        if (!e.available()) { status.text = "On-device speech recognition is not ready on this phone."; return }
        stop()
        mic.setColorFilter(ink(R.color.listening)); status.text = "Listening…"
        e.listen(onPartial = { preview.text = it }, onFinal = { heard ->
            if (heard.isNotBlank()) { host.connection()?.commitText(heard.trim() + " ", 1); status.text = "Dictated. Choose Fix or Improve to polish it." }
        }, onStop = { problem -> mic.setColorFilter(null); problem?.let { status.text = it.message } })
    }

    companion object {
        /** How to turn the panel on: Android lets only the owner enable an input method. */
        fun setup(activity: android.app.Activity) {
            android.app.AlertDialog.Builder(activity).setTitle("Ægentica keys")
                .setMessage("An AI panel beside your keyboard. In any text field, switch to it with the keyboard button at the bottom of the screen, then Fix, Improve, Shorten, Translate or Continue what you wrote, reply to a copied message, or dictate. Tap the keyboard icon to go back to Gboard.\n\n" +
                    "1. Open keyboard settings and turn on Ægentica keys.\n2. Android warns that a keyboard can read what you type: Ægentica reads a field only when you tap an action, never in password fields, and sends it only to the model chosen for the assistant.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open keyboard settings") { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                }.showProtected(activity)
        }

        /** Chip, then what the model is asked to do with the field's text. */
        val ACTIONS = listOf(
            "Fix" to "Correct spelling, grammar and punctuation. Keep the wording, tone and language.",
            "Improve" to "Make it read clearly and naturally. Keep the meaning, tone and language.",
            "Shorter" to "Make it shorter and tighter. Keep the meaning and language.",
            "Friendlier" to "Make it warmer and friendlier. Keep the meaning and language.",
            "Formal" to "Make it more formal and professional. Keep the meaning and language.",
            "Translate" to "Translate it. Swedish becomes English; any other language becomes Swedish.",
            "Continue" to "Continue the text naturally from where it ends, in the same voice and language. Return the whole text including the continuation.")
    }
}

/** Reading and writing the focused field through Android's input connection. */
object KeyboardText {
    const val MAX = 6000

    /** [field] is false for text that did not come from the field, such as the clipboard. */
    data class Source(val text: String, val selected: Boolean, val field: Boolean = true, val before: Int = 0, val after: Int = 0)

    fun secret(info: EditorInfo?): Boolean = info != null && secret(info.inputType)

    fun secret(type: Int): Boolean {
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** The selection if there is one, otherwise the whole field. */
    fun read(connection: InputConnection): Source? {
        connection.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { return Source(it.take(MAX), selected = true) }
        val before = connection.getTextBeforeCursor(MAX, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(MAX, 0)?.toString().orEmpty()
        val all = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX }, 0)?.text?.toString() ?: (before + after)
        return all.takeIf { it.isNotBlank() }?.let { Source(it.take(MAX), selected = false, before = before.length, after = after.length) }
    }

    /** Swap the text that was read for [text]: the selection, or the whole field. */
    fun replace(connection: InputConnection, source: Source, text: String) {
        connection.beginBatchEdit()
        if (!source.selected) {
            // Select everything around the cursor, then type over it.
            val before = connection.getTextBeforeCursor(MAX, 0)?.length ?: source.before
            val after = connection.getTextAfterCursor(MAX, 0)?.length ?: source.after
            connection.deleteSurroundingText(before, after)
        }
        connection.commitText(text, 1)
        connection.endBatchEdit()
    }
}
