package com.example.surfacelab

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Appears in the text-selection popup menu anywhere in the system.
 * Returning EXTRA_PROCESS_TEXT replaces the selection, but only when the
 * source field is editable -- EXTRA_PROCESS_TEXT_READONLY says which
 * case this is.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent
            .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            .orEmpty()

        val readOnly = intent
            .getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val transformed = selected.uppercase()

        if (readOnly) {
            Toast.makeText(this, transformed, Toast.LENGTH_LONG).show()
        } else {
            setResult(
                RESULT_OK,
                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, transformed)
            )
        }

        // A translucent activity that never finishes leaves a dead
        // window on screen, so finish unconditionally.
        finish()
    }
}
