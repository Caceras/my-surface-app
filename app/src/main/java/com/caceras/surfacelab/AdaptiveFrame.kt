package com.caceras.surfacelab

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Keep text readable in wide windows while letting the system resize the activity. */
class AdaptiveFrame(context: Context, content: View) : FrameLayout(context) {
    init {
        setBackgroundColor(context.ink(R.color.chat_bg))
        addView(content, LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        getChildAt(0).layoutParams.width = minOf(available, context.dp(720)).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
