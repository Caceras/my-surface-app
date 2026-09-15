package com.caceras.surfacelab

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

/** Streaming surfaces follow new text until the reader deliberately scrolls away. */
class ReadingScrollView(context: Context) : ScrollView(context) {
    var following = true
        private set
    var onFollowingChanged: ((Boolean) -> Unit)? = null
    private var posted = false

    init {
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) follow(false)
            false
        }
    }

    private fun follow(value: Boolean) {
        if (following == value) return
        following = value
        onFollowingChanged?.invoke(value)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t < oldt) follow(false)
        val end = ((getChildAt(0)?.height ?: 0) - height + paddingTop + paddingBottom).coerceAtLeast(0)
        if (t >= end) follow(true)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val preserve = !following
        val position = scrollY
        super.onLayout(changed, l, t, r, b)
        if (preserve) scrollTo(0, position)
    }

    fun latest() { follow(true); contentChanged() }

    fun contentChanged() {
        if (!following || posted) return
        posted = true
        post {
            posted = false
            if (following && isAttachedToWindow) scrollTo(0, getChildAt(0)?.height ?: 0)
        }
    }
}
