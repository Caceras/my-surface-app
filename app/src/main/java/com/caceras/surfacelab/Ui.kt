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
    val baseLeft = paddingLeft
    val baseRight = paddingRight
    val baseTop = paddingTop + context.dp(extraTop)
    val baseBottom = paddingBottom + context.dp(extraBottom)
    setOnApplyWindowInsetsListener { view, insets ->
        val top: Int
        val bottom: Int
        val left: Int
        val right: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            top = bars.top
            bottom = bars.bottom
            left = bars.left
            right = bars.right
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
        }
        view.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom)
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
        minHeight = context.dp(48)
        minimumHeight = context.dp(48)
        setPadding(context.dp(14), 0, context.dp(14), 0)
        setOnClickListener { onTap() }
        (layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin = context.dp(8)
    }

/** Keep a bottom composer visually attached to the keyboard during its native transition. */
fun View.followKeyboardMotion() {
    if (Build.VERSION.SDK_INT >= 30) setWindowInsetsAnimationCallback(KeyboardMotion(this))
}

@android.annotation.TargetApi(30)
internal class KeyboardMotion(private val view: View) : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
    private val location = IntArray(2)
    private var start = 0
    private var offset = 0f

        override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
            if (animation.typeMask and WindowInsets.Type.ime() == 0) return
            view.getLocationOnScreen(location)
            start = location[1]
        }
        override fun onStart(animation: android.view.WindowInsetsAnimation, bounds: android.view.WindowInsetsAnimation.Bounds): android.view.WindowInsetsAnimation.Bounds {
            if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                view.translationY = 0f
                view.getLocationOnScreen(location)
                offset = (start - location[1]).toFloat()
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) view.translationY = offset
            }
            return bounds
        }
        override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<android.view.WindowInsetsAnimation>): WindowInsets {
            val ime = runningAnimations.firstOrNull { it.typeMask and WindowInsets.Type.ime() != 0 }
            if (ime != null) view.translationY = if (android.animation.ValueAnimator.areAnimatorsEnabled()) offset * (1f - ime.interpolatedFraction) else 0f
            return insets
        }
        override fun onEnd(animation: android.view.WindowInsetsAnimation) {
            if (animation.typeMask and WindowInsets.Type.ime() != 0) { view.translationY = 0f; offset = 0f }
        }
}
