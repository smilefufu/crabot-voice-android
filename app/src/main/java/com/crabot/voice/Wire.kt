package com.crabot.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object Wire {
    const val PROTOCOL_VERSION = 7
    internal fun requirePairingVersion(version: Int, saved: Boolean) {
        require(version == PROTOCOL_VERSION || (saved && version in 3..6)) { "配对版本不兼容，需要 v$PROTOCOL_VERSION 配对码" }
    }
    const val MAX_INPUT = 240_000
    const val MAX_OUTPUT = 480_000
    fun frame(kind: Int, id: String, sequence: Int, pcm: ShortArray): ByteArray {
        require(kind == 1 || kind == 2)
        require(sequence >= 0 && pcm.isNotEmpty() && pcm.size <= if (kind == 1) 320 else 480)
        val uuid = UUID.fromString(id)
        return ByteBuffer.allocate(21 + pcm.size * 2).apply {
            put(kind.toByte()); putLong(uuid.mostSignificantBits); putLong(uuid.leastSignificantBits)
            order(ByteOrder.LITTLE_ENDIAN); putInt(sequence); pcm.forEach { putShort(it) }
        }.array()
    }
    data class AudioFrame(val id: String, val sequence: Int, val samples: ShortArray)
    fun output(bytes: ByteArray): AudioFrame {
        require(bytes.size in 23..981 && (bytes.size - 21) % 2 == 0)
        val b = ByteBuffer.wrap(bytes)
        require(b.get().toInt() == 2)
        val id = UUID(b.long, b.long).toString()
        b.order(ByteOrder.LITTLE_ENDIAN)
        val sequence = b.int
        require(sequence >= 0)
        return AudioFrame(id, sequence, ShortArray(b.remaining() / 2) { b.short })
    }
}
