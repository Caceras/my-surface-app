package com.caceras.surfacelab

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * View.setPadding takes **pixels**, not dp. Every layout in this app was built
 * in code with raw numbers, which meant the spacing shrank as screen density
 * rose: 56 "units" of side padding is a comfortable 56dp on an old 1x device
 * and about 16dp on a Pixel 10 Pro XL. The app looked cramped on exactly the
 * hardware it was written for.
 */
fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun View.padDp(left: Int, top: Int, right: Int, bottom: Int) =
    setPadding(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))

/**
 * From Android 15, an app targeting SDK 35+ is laid out edge to edge whether it
 * asks to be or not, so the first thing on screen sits underneath the status
 * bar unless the insets are consumed. This keeps the original padding and adds
 * the system bars on top of it.
 */
fun View.padForSystemBars(extraTop: Int = 0, extraBottom: Int = 0) {
    val baseTop = paddingTop + context.dp(extraTop)
    val baseBottom = paddingBottom + context.dp(extraBottom)
    setOnApplyWindowInsetsListener { view, insets ->
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            top = bars.top
            bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        view.setPadding(view.paddingLeft, baseTop + top, view.paddingRight, baseBottom + bottom)
        insets
    }
    requestApplyInsets()
}

/** A small, tappable suggestion. Framework only -- no chip library involved. */
fun suggestionButton(context: Context, text: String, onTap: () -> Unit) =
    android.widget.Button(context).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        minHeight = context.dp(36)
        minimumHeight = context.dp(36)
        setPadding(context.dp(14), 0, context.dp(14), 0)
        setOnClickListener { onTap() }
        (layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin = context.dp(8)
    }
