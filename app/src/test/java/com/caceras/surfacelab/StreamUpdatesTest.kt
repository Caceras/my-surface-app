package com.caceras.surfacelab

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class StreamUpdatesTest {
    @Test fun `first words are immediate and a burst paints only the latest text`() {
        val painted = mutableListOf<String>()
        val updates = StreamUpdates { painted.add(it) }
        updates.offer("First")
        repeat(100) { updates.offer("First $it") }
        assertEquals(listOf("First"), painted)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(listOf("First", "First 99"), painted)
    }

    @Test fun `cancellation drops delayed text and the next request starts immediately`() {
        val painted = mutableListOf<String>()
        val updates = StreamUpdates { painted.add(it) }
        updates.offer("Old")
        updates.offer("Old pending")
        updates.cancel()
        updates.offer("New")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf("Old", "New"), painted)
    }
}
