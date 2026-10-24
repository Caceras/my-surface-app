package com.caceras.surfacelab

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rabbit's poses and motion rules, ported from Glimt so it looks and moves the same on every
 * surface (apps/chatbar/src/lib/alo.ts in Caceras/awesomeseo-monorepo, master c5f00f9): which
 * pose it takes, where it lands beside what the agent works on, its trips, and the small things it
 * does on its own. Pure, so the choices are tested. The sprites are Glimt's 13, cut from Riki's
 * own drawings (res/drawable-nodpi/rabbit_*.webp); all face right except [Pose.TURN]. The rabbit
 * has no name.
 */
object RabbitMotion {
    /** Each pose, its sprite, and the sprite's size in pixels (all at one scale). */
    enum class Pose(val drawable: Int, val w: Int, val h: Int) {
        SIT(R.drawable.rabbit_sit, 215, 301),
        STAND(R.drawable.rabbit_stand, 173, 263),
        LOOK(R.drawable.rabbit_look, 206, 300),
        TURN(R.drawable.rabbit_turn, 192, 259),
        BEG(R.drawable.rabbit_beg, 160, 297),
        WAVE(R.drawable.rabbit_wave, 206, 300),
        POUNCE(R.drawable.rabbit_pounce, 264, 237),
        HOP(R.drawable.rabbit_hop, 185, 259),
        LEAP(R.drawable.rabbit_leap, 286, 245),
        WALK(R.drawable.rabbit_walk, 191, 273),
        REST(R.drawable.rabbit_rest, 264, 227),
        LOAF(R.drawable.rabbit_loaf, 228, 250),
        SLEEP(R.drawable.rabbit_sleep, 276, 243),
    }

    /** Sprite pixels to dp on a phone: sitting, the rabbit is about 60 dp tall (Glimt's 0.2 on touch screens). */
    const val SCALE = 0.2f

    /** What the rabbit acts out for a step. */
    enum class Act { CLICK, TYPE, SELECT, EDIT, READ, LOOK, SCROLL, NAVIGATE }

    /** The phone's tools as the same acts as Glimt's page tools. */
    val ACT_OF: Map<String, Act> = mapOf(
        "phone_tap" to Act.CLICK,
        "phone_long_press" to Act.CLICK,
        "phone_type" to Act.TYPE,
        "phone_snapshot" to Act.LOOK,
        "phone_find" to Act.LOOK,
        "phone_screenshot" to Act.LOOK,
        "phone_highlight" to Act.LOOK,
        "phone_wait_for" to Act.LOOK,
        "phone_scroll" to Act.SCROLL,
        "phone_swipe" to Act.SCROLL,
        "phone_open_app" to Act.NAVIGATE,
        "phone_open_url" to Act.NAVIGATE,
        "phone_back" to Act.NAVIGATE,
        "phone_home" to Act.NAVIGATE,
        "phone_recents" to Act.NAVIGATE,
    )

    fun actPose(act: Act): Pose = when (act) {
        Act.CLICK, Act.TYPE, Act.SELECT -> Pose.POUNCE
        Act.EDIT -> Pose.WAVE
        Act.READ, Act.LOOK -> Pose.LOOK
        Act.SCROLL -> Pose.WALK
        Act.NAVIGATE -> Pose.LEAP
    }

    /** The pose for a tool, or a look for anything else. */
    fun poseFor(tool: String): Pose = ACT_OF[tool]?.let(::actPose) ?: Pose.LOOK

    /** The pose between actions: what the agent is doing, then how long the rabbit has been left alone. */
    fun restPose(waiting: Boolean, thinking: Boolean, busy: Boolean, open: Boolean, typing: Boolean = false, doneMs: Long = Long.MAX_VALUE, idleMs: Long = 0): Pose = when {
        waiting -> Pose.BEG
        busy -> if (thinking) Pose.TURN else Pose.LOOK
        doneMs < 2200 -> Pose.WAVE
        typing -> Pose.LOOK
        open -> Pose.STAND
        idleMs > 180_000 -> Pose.SLEEP
        idleMs > 60_000 -> Pose.LOAF
        else -> Pose.SIT
    }

    /** A small thing the rabbit does on its own while it waits; [dx] in sprite pixels. */
    data class Fidget(val pose: Pose, val dx: Int, val ms: Long)
    val FIDGETS = listOf(
        Fidget(Pose.LOOK, 0, 1600), Fidget(Pose.TURN, 0, 1500), Fidget(Pose.SIT, 44, 1700), Fidget(Pose.STAND, 0, 1300),
        Fidget(Pose.WALK, 70, 1900), Fidget(Pose.SIT, -36, 1500), Fidget(Pose.WAVE, 0, 1400),
    )
    /** [r] (0 to 1) picks one. */
    fun fidget(r: Double): Fidget = FIDGETS[(r * FIDGETS.size).toInt().coerceIn(0, FIDGETS.size - 1)]
    /** Between fidgets: 6 to 14 seconds, so it never looks like a loop. */
    fun fidgetDelay(r: Double): Long = 6000L + (r * 8000).roundToInt()

    /** A short hop for a short way, a leap across the screen ([distance] in dp). */
    fun travelPose(distance: Float): Pose = if (distance < 220) Pose.HOP else Pose.LEAP

    /** Milliseconds for a trip: quick enough to feel live, slow enough to follow with the eye; none with reduced motion. */
    fun travelMs(distance: Float, reduced: Boolean = false): Long =
        if (reduced || distance < 4) 0 else min(620f, max(240f, 200 + distance * 0.55f)).roundToInt().toLong()

    /** Hops in a trip: one per ~160 dp, at least one. */
    fun hops(distance: Float): Int = max(1, (distance / 160).roundToInt())

    /** Whether a sprite drawn for [pose] must be mirrored to face right (true) or left. */
    fun flipFor(pose: Pose, faceRight: Boolean) = faceRight == (pose == Pose.TURN)

    /** Where the rabbit stands: its feet ([x] its left edge, [y] its bottom) and whether to mirror it. */
    data class Spot(val x: Int, val y: Int, val flip: Boolean)

    /**
     * Where the rabbit stands to work on an element: beside it on the left, feet level with its
     * bottom edge, or on its right when there is no room; inside the top corner of something
     * taller than the screen; always on screen. All in one unit (pixels on the phone).
     */
    fun landing(target: Rect, w: Int, h: Int, viewW: Int, viewH: Int, pose: Pose, gap: Int = 6, margin: Int = 8): Spot {
        var x = target.left - w - gap
        var faceRight = true
        if (x < margin) { x = target.right + gap; faceRight = false }
        if (x + w > viewW - margin) {
            // No room on either side: inside the element's top-left corner.
            x = max(margin, min(target.left + gap, viewW - w - margin))
            faceRight = true
        }
        // An element partly off screen: the rabbit stays on screen next to it.
        x = max(margin, min(x, viewW - w - margin))
        var y = if (target.height() > viewH * 0.6f) target.top + h + 24 else target.bottom
        y = max(h + margin, min(y, viewH - margin))
        return Spot(x, y, flipFor(pose, faceRight))
    }
}
