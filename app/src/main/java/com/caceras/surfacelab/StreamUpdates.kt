package com.caceras.surfacelab

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Paint the first chunk immediately, then coalesce bursts without delaying speech. */
class StreamUpdates(private val paint: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var next: String? = null
    private var lastPaint: Long? = null
    private var scheduled = false
    private val flush = Runnable {
        scheduled = false
        val text = next
        next = null
        if (text != null) {
            lastPaint = SystemClock.uptimeMillis()
            paint(text)
        }
    }

    fun offer(text: String) {
        next = text
        val elapsed = lastPaint?.let { SystemClock.uptimeMillis() - it } ?: 48L
        if (!scheduled && elapsed >= 48L) flush.run()
        else if (!scheduled) {
            scheduled = true
            handler.postDelayed(flush, (48L - elapsed).coerceAtLeast(0))
        }
    }

    /** Final, cancelled and replaced requests must never receive an older queued chunk. */
    fun cancel() {
        handler.removeCallbacks(flush)
        next = null
        scheduled = false
        lastPaint = null
    }
}
