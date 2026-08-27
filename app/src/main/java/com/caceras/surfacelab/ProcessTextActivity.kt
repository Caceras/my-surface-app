package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
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
 */
class ProcessTextActivity : Activity() {

    private var dialog: AlertDialog? = null
    private var readOnly = false
    private lateinit var selection: String
    private lateinit var task: Task

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
        Prompts.SUGGESTIONS.forEach { suggestion ->
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

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 12, 20, 0)
            addView(input)
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
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()

        // The streaming dialog is the progress indicator: text lands in it as
        // the model produces it, so there is never a blank spinner.
        val stream = TextView(this).apply {
            text = getString(R.string.working)
            textSize = 15f
            padDp(20, 12, 20, 6)
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

        BrainProvider.get().run(
            context = this,
            task = task,
            input = selection,
            instruction = instruction,
            onPartial = { partial ->
                stream.text = partial
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        ) { result ->
            if (result.ok) ResultStore.save(this, task, result.text)
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

    override fun onDestroy() {
        // A dialog still showing when the activity goes away leaks its window.
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private companion object {
        const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
    }
}
