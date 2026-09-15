package com.caceras.surfacelab

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Select messages by role, not by their current nesting or button text. */
internal fun chatMessages(root: View): List<String> {
    val messages = mutableListOf<String>()
    fun visit(view: View) {
        if (view is TextView && (view.tag == "user-message" || view.tag == "assistant-message")) {
            messages.add(view.text.toString())
        }
        if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
    }
    visit(root)
    return messages
}
