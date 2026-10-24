package com.caceras.surfacelab

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.caceras.surfacelab.RabbitMotion.Pose
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** The rabbit's poses and motion, the same rules as Glimt's (apps/chatbar/tests/alo.test.ts). */
@RunWith(AndroidJUnit4::class)
class RabbitMotionTest {
    private val view = 1280 to 800
    private fun land(left: Int, top: Int, width: Int, height: Int, pose: Pose) =
        RabbitMotion.landing(Rect(left, top, left + width, top + height), 50, 70, view.first, view.second, pose)

    /** A WebP's canvas size, from its VP8X header. */
    private fun size(drawable: Int): Pair<Int, Int> {
        val b = RuntimeEnvironment.getApplication().resources.openRawResource(drawable).use { it.readNBytes(30) }
        assertEquals("VP8X", String(b, 12, 4, Charsets.US_ASCII))
        fun u24(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)
        return (u24(24) + 1) to (u24(27) + 1)
    }

    @Test fun `every pose has its sprite, and the sizes match the files`() {
        assertEquals(13, Pose.entries.size)
        Pose.entries.forEach { p -> assertEquals(p.name, p.w to p.h, size(p.drawable)) }
    }

    @Test fun `it lands beside the element, feet on its bottom edge, facing it, and never off screen`() {
        assertEquals(RabbitMotion.Spot(344, 340, false), land(400, 300, 120, 40, Pose.POUNCE))
        val right = land(20, 300, 120, 40, Pose.POUNCE)
        assertEquals(146, right.x); assertTrue(right.flip)
        val tall = land(0, -200, 1280, 3000, Pose.LOOK)
        assertTrue(tall.x >= 8 && tall.y >= 70 + 8 && tall.y <= 800 - 8)
        assertTrue(RabbitMotion.flipFor(Pose.TURN, true)); assertFalse(RabbitMotion.flipFor(Pose.SIT, true))
        listOf(Rect(-500, -500, -490, -490), Rect(5000, 5000, 5010, 5010), Rect(1270, 795, 1280, 805)).forEach { r ->
            val s = RabbitMotion.landing(r, 50, 70, 1280, 800, Pose.HOP)
            assertTrue("x ${s.x}", s.x >= 8 && s.x + 50 <= 1280 - 8)
            assertTrue("y ${s.y}", s.y - 70 >= 8 && s.y <= 800 - 8)
        }
    }

    @Test fun `poses follow what Ægentica is doing, and every phone step is acted out`() {
        fun rest(waiting: Boolean = false, thinking: Boolean = false, busy: Boolean = false, open: Boolean = false, typing: Boolean = false, doneMs: Long = Long.MAX_VALUE, idleMs: Long = 0) =
            RabbitMotion.restPose(waiting, thinking, busy, open, typing, doneMs, idleMs)
        assertEquals(Pose.BEG, rest(waiting = true, busy = true))
        assertEquals(Pose.TURN, rest(busy = true, thinking = true))
        assertEquals(Pose.LOOK, rest(busy = true))
        assertEquals(Pose.WAVE, rest(doneMs = 500))
        assertEquals(Pose.STAND, rest(open = true))
        assertEquals(Pose.LOOK, rest(open = true, typing = true))
        assertEquals(Pose.SIT, rest())
        assertEquals(Pose.LOAF, rest(idleMs = 90_000))
        assertEquals(Pose.SLEEP, rest(idleMs = 400_000))
        assertEquals(Pose.POUNCE, RabbitMotion.actPose(RabbitMotion.Act.CLICK))
        assertEquals(Pose.WAVE, RabbitMotion.actPose(RabbitMotion.Act.EDIT))
        assertEquals(Pose.LOOK, RabbitMotion.actPose(RabbitMotion.Act.READ))
        val phone = Playbook.get(RuntimeEnvironment.getApplication()).tools.filter { it.target == "phone" }.map { it.name }
        phone.forEach { assertNotNull("no act for $it", RabbitMotion.ACT_OF[it]) }
        assertEquals(Pose.LEAP, RabbitMotion.poseFor("phone_open_app"))
        assertEquals(Pose.WALK, RabbitMotion.poseFor("phone_scroll"))
    }

    @Test fun `trips are quick, longer ones leap, reduced motion jumps, and fidgets never keep a beat`() {
        assertEquals(Pose.HOP, RabbitMotion.travelPose(100f)); assertEquals(Pose.LEAP, RabbitMotion.travelPose(600f))
        assertEquals(0L, RabbitMotion.travelMs(0f)); assertEquals(0L, RabbitMotion.travelMs(500f, reduced = true))
        assertTrue(RabbitMotion.travelMs(50f) >= 240 && RabbitMotion.travelMs(5000f) <= 620)
        assertEquals(1, RabbitMotion.hops(40f)); assertEquals(5, RabbitMotion.hops(800f))
        assertEquals(RabbitMotion.FIDGETS.first(), RabbitMotion.fidget(0.0))
        assertEquals(RabbitMotion.FIDGETS.last(), RabbitMotion.fidget(0.999)); assertEquals(RabbitMotion.FIDGETS.last(), RabbitMotion.fidget(1.0))
        assertEquals(RabbitMotion.FIDGETS.size, (0 until 100).map { RabbitMotion.fidget(it / 100.0) }.toSet().size)
        RabbitMotion.FIDGETS.forEach { assertTrue(it.ms in 1000..2500 && kotlin.math.abs(it.dx) < 100) }
        assertEquals(6000L, RabbitMotion.fidgetDelay(0.0)); assertEquals(14000L, RabbitMotion.fidgetDelay(1.0))
    }
}
