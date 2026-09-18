package com.caceras.surfacelab

import android.app.Activity
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class KeyboardMotionTest {
    @Test fun `composer tracks the keyboard and resets its translation at the end`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val composer = View(activity)
        root.addView(composer)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        composer.layout(0, 600, 400, 700)
        val callback = KeyboardMotion(composer)
        val animation = WindowInsetsAnimation(WindowInsets.Type.ime(), LinearInterpolator(), 250)
        callback.onPrepare(animation)
        composer.layout(0, 300, 400, 400)
        callback.onStart(animation, WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, 300)))
        assertEquals(300f, composer.translationY, 0.1f)
        animation.fraction = 0.5f
        callback.onProgress(WindowInsets.Builder().build(), mutableListOf(animation))
        assertEquals(150f, composer.translationY, 0.1f)
        callback.onEnd(animation)
        assertEquals(0f, composer.translationY, 0.1f)
    }
}
