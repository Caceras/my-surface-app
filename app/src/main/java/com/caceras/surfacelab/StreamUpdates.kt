package com.caceras.surfacelab

import android.view.Choreographer

/** Immediate first paint, then at most one latest-value update per display frame. */
class StreamUpdates(private val paint: (String) -> Unit) {
    private val frames = Choreographer.getInstance()
    private var next: String? = null
    private var painted: String? = null
    private var scheduled = false
    private val flush = Choreographer.FrameCallback {
        scheduled = false
        val text = next
        next = null
        if (text != null && text != painted) {
            painted = text
            paint(text)
        }
    }

    fun offer(text: String) {
        if (text == (next ?: painted)) return
        if (painted == null && !scheduled) {
            painted = text
            paint(text)
            return
        }
        next = text
        if (!scheduled) {
            scheduled = true
            frames.postFrameCallback(flush)
        }
    }

    /** A finished, cancelled or replaced request must never paint a stale frame. */
    fun cancel() {
        frames.removeFrameCallback(flush)
        next = null
        painted = null
        scheduled = false
    }
}
