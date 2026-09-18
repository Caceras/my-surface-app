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
fun Context.pill(value: String, primary: Boolean = false, onClick: () -> Unit) = label(value, 14f).apply {
    medium()
    gravity = Gravity.CENTER
    minHeight = dp(48)
    minWidth = dp(48)
    padDp(16, 10, 16, 10)
    setTextColor(ink(if (primary) R.color.on_accent else R.color.text_primary))
    val shape = surface(if (primary) R.color.accent else R.color.chip_bg, 18)
    background = RippleDrawable(ColorStateList.valueOf(ink(R.color.outline)), shape, null)
    isFocusable = true
    buttonSemantics()
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
    NativePrivacy.apply(this, target)
    val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        target.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        @Suppress("DEPRECATION")
        target.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }
}

/** Custom-styled text controls still announce their native action role. */
fun View.buttonSemantics() {
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    }
}

/** One native toggle treatment: predictable text, contrast and spacing in either theme. */
fun Context.preferenceSwitch(title: String, checked: Boolean = false, change: (Boolean) -> Unit) =
    android.widget.Switch(this).apply {
        text = title
        textSize = 16f
        setTextColor(ink(R.color.text_primary))
        minHeight = dp(56)
        switchPadding = dp(16)
        padDp(0, 8, 0, 8)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ink(R.color.accent_text), ink(R.color.text_dim)))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ink(R.color.presence_bg), ink(R.color.outline)))
        isChecked = checked
        setOnCheckedChangeListener { _, value -> change(value) }
    }

fun Context.sheetHeader(title: String, close: () -> Unit) = android.widget.LinearLayout(this).apply {
    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
    tag = "sheet-header"
    gravity = Gravity.CENTER_VERTICAL
    addView(label(title, 22f).apply { medium(); isAccessibilityHeading = true },
        android.widget.LinearLayout.LayoutParams(0, -2, 1f))
    addView(pill("Done", onClick = close), android.widget.LinearLayout.LayoutParams(-2, -2))
}

/** Search and action fields request the same private, readable native editor. */
fun android.widget.EditText.styleField() {
    textSize = 16f
    setTextColor(context.ink(R.color.text_primary))
    setHintTextColor(context.ink(R.color.text_dim))
    background = context.surface(R.color.composer_bg, 16, true)
    padDp(16, 12, 16, 12)
    minHeight = context.dp(48)
    imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}

/** Dictation feedback respects Android's remove-animation setting. */
fun View.speechLevel(rms: Float) {
    val value = if (android.animation.ValueAnimator.areAnimatorsEnabled()) 1f + rms.coerceIn(0f, 10f) / 70f else 1f
    scaleX = value
    scaleY = value
}
