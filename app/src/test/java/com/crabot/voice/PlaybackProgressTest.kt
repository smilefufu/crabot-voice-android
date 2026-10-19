package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressTest {
    private val id = "00112233-4455-6677-8899-aabbccddeeff"
    @Test fun unknownLengthEndsWithVerifiedTotalAndShortTail() {
        val p = PlaybackProgress(id)
        p.add(Wire.AudioFrame(id, 0, ShortArray(480)))
        assertNull(p.count)
        p.add(Wire.AudioFrame(id, 1, ShortArray(17))); p.finish(497)
        assertEquals(497L, p.count)
        assertThrows(IllegalArgumentException::class.java) { p.add(Wire.AudioFrame(id, 2, ShortArray(480))) }
    }
    @Test fun missingFramesWrongIdsOrSamplesCannotComplete() {
        val p = PlaybackProgress(id)
        assertThrows(IllegalArgumentException::class.java) { p.add(Wire.AudioFrame(id, 1, ShortArray(480))) }
        assertThrows(IllegalArgumentException::class.java) { p.add(Wire.AudioFrame("other", 0, ShortArray(480))) }
        p.add(Wire.AudioFrame(id, 0, ShortArray(20)))
        assertThrows(IllegalArgumentException::class.java) { p.add(Wire.AudioFrame(id, 1, ShortArray(480))) }
        assertThrows(IllegalArgumentException::class.java) { p.finish(480) }
    }
    @Test fun threeMinutesRemainOneOrderedPlayback() {
        val p = PlaybackProgress(id)
        repeat(9_000) { p.add(Wire.AudioFrame(id, it.toLong(), ShortArray(480))) }
        p.finish(4_320_000L)
        assertEquals(4_320_000L, p.count)
    }
    @Test fun finiteManagementLimitRemainsIndependent() {
        val p = PlaybackProgress(id, Wire.MAX_OUTPUT.toLong())
        repeat(1_000) { p.add(Wire.AudioFrame(id, it.toLong(), ShortArray(480))) }
        assertThrows(IllegalArgumentException::class.java) { p.add(Wire.AudioFrame(id, 1_000, ShortArray(480))) }
    }
}
