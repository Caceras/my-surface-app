package com.caceras.surfacelab

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.TextView

/** Shared native surfaces, typography and feedback across every entry point. */
fun Context.ink(id: Int) = getColor(id)
fun Context.surface(fill: Int, radius: Int = 24, stroke: Boolean = false) =
    GradientDrawable().apply {
        setColor(ink(fill))
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), ink(R.color.outline))
    }
fun Context.label(value: String, size: Float = 16f, dim: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(ink(if (dim) R.color.text_dim else R.color.text_primary))
    fontFeatureSettings = "kern"
    setLineSpacing(dp(2).toFloat(), 1.08f)
}
fun TextView.medium() { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
fun Context.pill(value: String, primary: Boolean = false, onClick: () -> Unit) = label(value, 15f).apply {
    medium()
    gravity = Gravity.CENTER
    minHeight = dp(52)
    minWidth = dp(48)
    padDp(20, 12, 20, 12)
    setTextColor(ink(if (primary) R.color.on_accent else R.color.text_primary))
    val shape = surface(if (primary) R.color.accent else R.color.chip_bg, 28)
    background = RippleDrawable(ColorStateList.valueOf(ink(R.color.outline)), shape, null)
    isFocusable = true
    setOnClickListener {
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onClick()
    }
}
fun Context.presence(size: Int = 88) = PresenceView(this, size)

/** Short, interruptible entry motion. Android's remove-animation preference wins. */
fun View.arrive() {
    if (!isAttachedToWindow || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
    translationY = context.dp(6).toFloat()
    animate().translationY(0f).setDuration(180)
        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}

fun Activity.readableSystemBars(target: android.view.Window = window) {
    val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        target.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        @Suppress("DEPRECATION")
        target.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }
}
