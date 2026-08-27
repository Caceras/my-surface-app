package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Appears in the text-selection popup anywhere in the system, once per
 * <activity-alias> the flavour registers. Which alias was tapped is read back
 * off the incoming component name, so one activity serves every task.
 *
 * Returning EXTRA_PROCESS_TEXT replaces the selection in place, but only when
 * the source field is editable -- EXTRA_PROCESS_TEXT_READONLY says which case
 * this is, and getting it wrong is how these actions silently do nothing.
 */
class ProcessTextActivity : Activity() {

    private var working: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent
            .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            .orEmpty()
            .trim()

        val readOnly = intent
            .getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val task = Task.fromComponent(componentName?.className)

        working = AlertDialog.Builder(this, DIALOG_THEME)
            .setMessage(getString(R.string.working))
            .setCancelable(false)
            .show()

        BrainProvider.get().run(this, task, selected) { result ->
            working?.dismiss()
            working = null

            if (result.ok) {
                ResultStore.save(this, task, result.text)
            }

            val caveat = result.note ?: Lang.caveat(task, selected)

            // Replacing the selection is the better outcome, but only when
            // there is nothing the user needs to read first.
            if (!readOnly && result.ok && caveat == null) {
                setResult(
                    RESULT_OK,
                    Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.text)
                )
                finish()
            } else {
                show(task, result, caveat, readOnly)
            }
        }
    }

    private fun show(
        task: Task,
        result: BrainResult,
        caveat: String?,
        readOnly: Boolean
    ) {
        val body = buildString {
            if (result.ok) append(result.text)
            if (caveat != null) {
                if (isNotEmpty()) append("\n\n")
                append(caveat)
            }
        }

        val builder = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(task.alias)
            .setMessage(body)
            .setOnDismissListener { finish() }
            .setNegativeButton(R.string.close) { d, _ -> d.dismiss() }

        if (result.ok) {
            builder.setPositiveButton(R.string.copy) { d, _ ->
                val clip = getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(ClipData.newPlainText(task.alias, result.text))
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

        builder.show()
    }

    override fun onDestroy() {
        // A dialog still showing when the activity goes away leaks its window.
        working?.dismiss()
        working = null
        super.onDestroy()
    }

    private companion object {
        const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
    }
}
