package com.caceras.surfacelab

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class EchoIndexTest {
    @Test fun `indexed check matches the original exact substring rule`() {
        val random = Random(18)
        for (task in Task.values()) {
            val instruction = Prompts.system(task).lowercase()
            val samples = mutableListOf("", "A normal answer.", "x".repeat(10000), instruction.uppercase())
            if (instruction.length >= 50) {
                for (start in 0..instruction.length - 50 step 7) {
                    samples += "prefix " + instruction.substring(start, start + 50) + " suffix"
                    samples += instruction.substring(start, start + 49)
                }
            }
            repeat(100) { samples += (0..random.nextInt(1, 600)).map { ('a'.code + random.nextInt(26)).toChar() }.joinToString("") }
            for (answer in samples) {
                val expected = instruction.length >= 50 && instruction.windowed(50).any { answer.lowercase().contains(it) }
                assertEquals("$task: ${answer.take(60)}", expected, Prompts.isEcho(answer, task))
            }
        }
    }
}
