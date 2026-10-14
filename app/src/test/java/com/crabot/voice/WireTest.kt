package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class WireTest {
    @Test fun wireUpgradeAcceptsSavedV3V4ButRequiresV5ForNewPairing() {
        Wire.requirePairingVersion(3, saved = true)
        Wire.requirePairingVersion(4, saved = true)
        Wire.requirePairingVersion(5, saved = false)
        Wire.requirePairingVersion(5, saved = true)
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(4, saved = false) }
        assertThrows(IllegalArgumentException::class.java) { Wire.requirePairingVersion(3, saved = false) }
        listOf(0, 1, 2, 6).forEach { version ->
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
        assertEquals(id, decoded.id); assertEquals(0x1020304, decoded.sequence); assertArrayEquals(pcm, decoded.samples)
    }
    @Test fun rejectsMalformedAndOversizeFrames() {
        val id = UUID.randomUUID().toString()
        listOf(ByteArray(21), ByteArray(24), ByteArray(983), Wire.frame(1, id, 0, shortArrayOf(1))).forEach { bad ->
            try { Wire.output(bad); fail("Accepted invalid output") } catch (_: IllegalArgumentException) {}
        }
        try { Wire.frame(1, id, 0, ShortArray(321)); fail("Accepted too many input samples") } catch (_: IllegalArgumentException) {}
        val negative = Wire.frame(2, id, 0, shortArrayOf(1)); ByteBuffer.wrap(negative, 17, 4).putInt(-1)
        try { Wire.output(negative); fail("Accepted negative sequence") } catch (_: IllegalArgumentException) {}
    }
}
