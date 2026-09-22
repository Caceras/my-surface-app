package com.caceras.surfacelab

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A lightweight native pager with no AndroidX dependency.
 * All pages stay mounted next to each other; swiping exposes the outgoing and incoming pages together.
 */
class WorkspacePager(context: Context) : HorizontalScrollView(context) {
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var page = 0
    private var downX = 0f
    private var downScroll = 0
    var onPageChanged: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setOnScrollChangeListener { _, x, _, _, _ ->
            val w = width.takeIf { it > 0 } ?: return@setOnScrollChangeListener
            val candidate = (x.toFloat() / w).roundToInt().coerceIn(0, (strip.childCount - 1).coerceAtLeast(0))
            if (candidate != page && abs(x - candidate * w) < w * 0.18f) {
                page = candidate
                onPageChanged?.invoke(page)
            }
        }
    }

    fun addPage(view: View) {
        strip.addView(view, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun pageCount() = strip.childCount

    fun currentPage() = page

    fun setPage(index: Int, smooth: Boolean = true) {
        val target = index.coerceIn(0, (strip.childCount - 1).coerceAtLeast(0))
        page = target
        post {
            if (smooth) smoothScrollTo(target * width, 0) else scrollTo(target * width, 0)
            onPageChanged?.invoke(target)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pageWidth = MeasureSpec.getSize(widthMeasureSpec)
        for (i in 0 until strip.childCount) {
            strip.getChildAt(i).layoutParams.width = pageWidth
        }
        strip.layoutParams.width = pageWidth * strip.childCount
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) post { scrollTo(page * w, 0) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downScroll = scrollX
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.x - downX
                val w = width.coerceAtLeast(1)
                val target = when {
                    abs(dx) > w * 0.18f -> page + if (dx < 0) 1 else -1
                    else -> ((scrollX + w / 2f) / w).toInt()
                }.coerceIn(0, (strip.childCount - 1).coerceAtLeast(0))
                setPage(target, true)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun fling(velocityX: Int) {
        val target = (page + if (velocityX > 0) 1 else -1)
            .coerceIn(0, (strip.childCount - 1).coerceAtLeast(0))
        setPage(target, true)
    }
}
