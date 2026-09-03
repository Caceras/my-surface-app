package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Appears in the text-selection popup anywhere in the system, once per
 * <activity-alias> the flavour registers. Which alias was tapped is read back
 * off the incoming component name, so one activity serves every task.
 *
 * "Ask" opens a prompt box first: the selection is the material, the user
 * supplies the question. The presets skip that step and send a fixed system
 * instruction instead.
 *
 * Returning EXTRA_PROCESS_TEXT replaces the selection in place, but only when
 * the source field is editable -- EXTRA_PROCESS_TEXT_READONLY says which case
 * this is, and getting it wrong is how these actions silently do nothing.
 *
 * The Ask box gets a microphone, and it is the highest-value one in the app:
 * your hands have already done the selecting, so asking out loud is genuinely
 * faster than typing. The presets do not, because a preset needs no words.
 * As everywhere else, the answer is spoken only if the question was.
 */
class ProcessTextActivity : Activity() {

    private var dialog: AlertDialog? = null
    private var readOnly = false
    private lateinit var selection: String
    private lateinit var task: Task

    private val ears by lazy { Ears(this) }
    private var mouth: Mouth? = null
    private var prompt: EditText? = null
    private var mic: ImageButton? = null

    /** True while the pending question came from the microphone. */
    private var askedAloud = false
    private var listening = false

    /** Set on the way out; see the same flag in MainActivity and docs/voice.md. */
    private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selection = intent
            .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            .orEmpty()
            .trim()

        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        task = Task.fromComponent(componentName?.className)

        if (selection.isEmpty()) {
            Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (task == Task.ASK) ask() else send("")
    }

    /** Free-form: the user writes the prompt, the selection is the material. */
    private fun ask() {
        val input = EditText(this).apply {
            hint = getString(R.string.ask_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
        }

        // A blank box is a worse prompt than a bad suggestion.
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            padDp(0, 4, 0, 4)
        }
        Prompts.ABOUT_SELECTION.forEach { suggestion ->
            chips.addView(suggestionButton(this, suggestion) {
                input.setText(suggestion)
                input.setSelection(suggestion.length)
            })
        }

        val preview = TextView(this).apply {
            text = if (selection.length > 220) selection.take(217) + "..." else selection
            textSize = 13f
            alpha = 0.6f
            padDp(0, 8, 0, 0)
        }

        prompt = input

        // No on-device recogniser means no microphone at all, rather than a
        // button that quietly sends audio somewhere. See docs/voice.md.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (ears.available()) {
                val button = ImageButton(this@ProcessTextActivity).apply {
                    setImageResource(R.drawable.ic_mic)
                    background = null
                    contentDescription = getString(R.string.mic)
                    val pad = dp(8)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { toggleListening() }
                }
                mic = button
                addView(button)
            }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 12, 20, 0)
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(HorizontalScrollView(this@ProcessTextActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            addView(preview)
        }

        dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.ask_title)
            .setView(body)
            .setPositiveButton(R.string.send) { _, _ ->
                send(input.text.toString())
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener { if (dialog != null) finish() }
            .show()
    }

    private fun send(instruction: String) {
        // Modality is inherited: this answer is spoken only because the
        // question was. The flag is consumed here.
        val aloud = askedAloud
        askedAloud = false
        ears.cancel()
        listening = false

        dialog?.setOnDismissListener(null)
        dialog?.dismiss()

        // The streaming dialog is the progress indicator: text lands in it as
        // the model produces it, so there is never a blank spinner.
        val stream = TextView(this).apply {
            text = getString(R.string.working)
            textSize = 15f
            padDp(20, 12, 20, 6)
            // Barge-in: touching the answer stops it being read aloud.
            setOnClickListener { mouth?.hush() }
        }
        val scroll = ScrollView(this).apply {
            addView(
                stream,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(task.alias)
            .setView(scroll)
            .setCancelable(false)
            .setNegativeButton(R.string.close) { d, _ -> d.dismiss() }
            .setOnDismissListener { finish() }
            .show()

        if (aloud) speaker().begin(ears.locale())

        Brains.get().run(
            context = this,
            task = task,
            input = selection,
            instruction = instruction,
            onPartial = { partial ->
                if (gone) return@run
                stream.text = partial
                if (aloud) mouth?.follow(partial)
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        ) { result ->
            if (gone) return@run
            if (result.ok) ResultStore.save(this, task, result.text)
            if (aloud && result.ok) mouth?.finish(result.text)
            val note = result.note ?: Lang.caveat(task, selection)

            // Replacing the selection is the better outcome, but only when
            // there is nothing the user needs to read first.
            if (!readOnly && result.ok && note == null && task != Task.ASK) {
                dialog?.setOnDismissListener(null)
                dialog?.dismiss()
                setResult(
                    RESULT_OK,
                    Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.text)
                )
                finish()
            } else {
                dialog?.setOnDismissListener(null)
                dialog?.dismiss()
                show(result, note)
            }
        }
    }

    private fun show(result: BrainResult, note: String?) {
        val body = buildString {
            if (result.ok) append(result.text)
            if (note != null) {
                if (isNotEmpty()) append("\n\n")
                append(note)
            }
        }

        val builder = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(task.alias)
            .setMessage(body)
            .setOnDismissListener { finish() }
            .setNegativeButton(R.string.close) { d, _ -> d.dismiss() }

        if (result.ok) {
            builder.setPositiveButton(R.string.copy) { d, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(task.alias, result.text))
                d.dismiss()
            }
            if (!readOnly) {
                builder.setNeutralButton(R.string.replace) { d, _ ->
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.text)
                    )
                    d.dismiss()
                }
            }
        }

        dialog = builder.show()
    }

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
        val input = prompt ?: return
        listening = true
        setMicActive(true)

        ears.listen(
            onLevel = { rms ->
                val scale = 1f + (rms.coerceIn(0f, 10f) / 20f)
                mic?.scaleX = scale
                mic?.scaleY = scale
            },
            onPartial = { partial ->
                input.setText(partial)
                input.setSelection(input.text.length)
            },
            onFinal = { text ->
                input.setText(text)
                input.setSelection(input.text.length)
                // The transcript stays editable here, unlike the hands-free
                // screen: you are already looking at the box, so a misheard
                // word is a fix rather than a redo. Send is still Send.
                askedAloud = true
            },
            onStop = { problem ->
                listening = false
                setMicActive(false)
                if (problem != null) {
                    Toast.makeText(this, problem.message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun setMicActive(active: Boolean) {
        mic?.setColorFilter(
            resources.getColor(
                if (active) R.color.listening else R.color.text_dim, theme
            )
        )
        if (!active) {
            mic?.scaleX = 1f
            mic?.scaleY = 1f
        }
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
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        gone = true
        // The microphone is not left held, and the engine is shut down rather
        // than merely stopped -- stop() is barge-in, not teardown.
        ears.cancel()
        mouth?.close()
        mouth = null
        // A dialog still showing when the activity goes away leaks its window.
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private companion object {
        const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
        const val MIC_REQUEST = 1
    }
}
