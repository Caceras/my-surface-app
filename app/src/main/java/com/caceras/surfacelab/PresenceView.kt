package com.caceras.surfacelab

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

/** Real state feedback using the existing mark. No idle animation or background work. */
// Nano brings transitive AppCompat classes, but this app uses framework themes
// and explicitly tints this image; it is not an AppCompatActivity custom view.
@android.annotation.SuppressLint("AppCompatCustomView")
class PresenceView(context: Context, size: Int = 88) : ImageView(context) {
    enum class Mode { REST, LISTENING, THINKING, SPEAKING }
    var mode = Mode.REST
        private set
    private var pulse: ObjectAnimator? = null

    init {
        tag = "presence"
        setImageResource(R.drawable.ic_surface)
        setColorFilter(context.ink(R.color.accent_text))
        background = context.surface(R.color.presence_bg, size / 2)
        padDp(size / 6, size / 6, size / 6, size / 6)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(next: Mode) {
        if (mode == next) return
        mode = next
        refresh()
    }

    fun level(rms: Float) {
        if (mode != Mode.LISTENING) return
        val value = 1f + rms.coerceIn(0f, 10f) / 70f
        if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(value).scaleY(value).setDuration(90).start()
        else { scaleX = 1f; scaleY = 1f }
    }

    private fun refresh() {
        pulse?.cancel()
        pulse = null
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        alpha = if (mode == Mode.REST) 0.65f else 1f
        if (!isAttachedToWindow || windowVisibility != View.VISIBLE || !isShown ||
            !ValueAnimator.areAnimatorsEnabled() || mode !in listOf(Mode.THINKING, Mode.SPEAKING)) return
        pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 0.7f, 1f).apply {
            duration = if (mode == Mode.THINKING) 1400 else 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refresh()
    }
    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        animate().cancel()
        super.onDetachedFromWindow()
    }
}
