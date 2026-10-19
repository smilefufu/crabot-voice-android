package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class WireTest {
    @Test fun wireUpgradePreservesSavedV3ThroughV8ButRequiresV9ForNewPairing() {
        Wire.requirePairingVersion(3, saved = true)
        Wire.requirePairingVersion(4, saved = true)
        Wire.requirePairingVersion(9, saved = false)
        Wire.requirePairingVersion(8, saved = true)
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(8, saved = false) }
        Wire.requirePairingVersion(7, saved = true)
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(7, saved = false) }
        Wire.requirePairingVersion(6, saved = true)
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(5, saved = false) }
        Wire.requirePairingVersion(5, saved = true)
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(4, saved = false) }
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(3, saved = false) }
        listOf(0, 1, 2, 10).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(version, saved = true) }
            assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(version, saved = false) }
        }
    }
    @Test fun preservesUuidLittleEndianSequenceAndPcm() {
        val id = UUID.randomUUID().toString()
        val pcm = shortArrayOf(-32768, 0, 32767)
        val frame = Wire.frame(2, id, 0x1020304, pcm)
        assertEquals(2, frame[0].toInt()); assertEquals(4, frame[17].toInt())
        val decoded = Wire.output(frame)
        assertEquals(id, decoded.id); assertEquals(0x1020304L, decoded.sequence); assertArrayEquals(pcm, decoded.samples)
    }
    @Test fun rejectsMalformedAndOversizeFrames() {
        val id = UUID.randomUUID().toString()
        listOf(ByteArray(21), ByteArray(24), ByteArray(983), Wire.frame(1, id, 0, shortArrayOf(1))).forEach { bad ->
            try { Wire.output(bad); fail("Accepted invalid output") } catch (_: IllegalArgumentException) {}
        }
        try { Wire.frame(1, id, 0, ShortArray(321)); fail("Accepted too many input samples") } catch (_: IllegalArgumentException) {}
        val negative = Wire.frame(2, id, 0, shortArrayOf(1)); ByteBuffer.wrap(negative, 17, 4).putInt(-1)
        assertEquals(0xffffffffL, Wire.output(negative).sequence)
    }
    @Test fun acceptsSafeLongCountsButRejectsFractionalOrUnboundedValues() {
        assertEquals(3_000_000_000L, Wire.sampleCount(3_000_000_000L))
        listOf(-1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, 9_007_199_254_740_992L, "123").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Wire.sampleCount(value) }
        }
    }
}
