package com.caceras.surfacelab

import android.view.Choreographer

/** Main-thread input: first words immediately, then at most one paint per display frame. */
class StreamUpdates(private val paint: (String) -> Unit) {
    private val frames = Choreographer.getInstance()
    private var next: String? = null
    private var last: String? = null
    private var scheduled = false
    private val flush = Choreographer.FrameCallback {
        scheduled = false
        val text = next
        next = null
        if (text != null && text != last) { last = text; paint(text) }
    }

    fun offer(text: String) {
        if (text == (next ?: last)) return
        if (last == null && !scheduled) {
            last = text
            paint(text)
        } else {
            next = text
            if (!scheduled) { scheduled = true; frames.postFrameCallback(flush) }
        }
    }

    /** A final response or cancellation must never be overwritten by an older frame. */
    fun cancel() {
        frames.removeFrameCallback(flush)
        next = null
        last = null
        scheduled = false
    }
}
