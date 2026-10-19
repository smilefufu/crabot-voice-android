package com.crabot.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

data class PlaybackReceipt(val scope: String, val id: String, val status: String, val played: Long?)

/** App-private metadata only; this file never contains audio, text or raw credentials. */
class PlaybackReceiptJournal(private val file: File) {
    private var receipts: List<PlaybackReceipt> = emptyList()
    init {
        if (file.exists()) {
            val original = file.readBytes()
            var version = 0
            val items = DataInputStream(ByteArrayInputStream(original)).use { input ->
                version = input.readInt(); require(version in 1..2) { "播放回执版本不兼容，原文件保留" }
                val size = input.readInt(); require(size in 0..128)
                val result = (0 until size).map {
                    val scope = input.readUTF(); val id = input.readUTF(); val status = input.readUTF()
                    val samples = if (version == 1) input.readInt().toLong() else input.readLong()
                    require(samples >= -1L)
                    if (version == 1) require(samples <= Wire.MAX_OUTPUT)
                    PlaybackReceipt(scope, id, status, samples.takeIf { it >= 0 }).also(::validate)
                }
                require(input.read() == -1)
                require(result.distinctBy { it.scope to it.id }.size == result.size)
                result
            }
            receipts = items
            if (version == 1) {
                val backup = File(file.parentFile, file.name + ".v1.bak")
                if (backup.exists()) require(backup.readBytes().contentEquals(original)) { "旧播放回执备份不一致，原文件保留" }
                else replaceAtomic(backup, original)
                require(backup.readBytes().contentEquals(original))
                save(items)
            }
        }
    }
    private fun validate(item: PlaybackReceipt) {
        require(item.scope.matches(Regex("[0-9a-f]{64}")))
        require(UUID.fromString(item.id).toString() == item.id)
        require(item.status in setOf("completed", "interrupted", "failed"))
        require(item.played == null || item.played in 0..Wire.MAX_SAFE_SAMPLES)
        if (item.status == "completed") require(item.played != null && item.played > 0)
    }
    @Synchronized fun pending(scope: String): List<PlaybackReceipt> = receipts.filter { it.scope == scope }.toList()
    @Synchronized fun remember(item: PlaybackReceipt) {
        validate(item)
        val old = receipts.find { it.scope == item.scope && it.id == item.id }
        if (old != null) { require(old == item) { "播放回执冲突" }; return }
        save(receipts + item)
    }
    @Synchronized fun confirm(scope: String, id: String) = save(receipts.filterNot { it.scope == scope && it.id == id })
    private fun save(next: List<PlaybackReceipt>) {
        require(next.size <= 128) { "播放回执待同步，请先恢复连接" }
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(2); output.writeInt(next.size)
                next.forEach { output.writeUTF(it.scope); output.writeUTF(it.id); output.writeUTF(it.status); output.writeLong(it.played ?: -1L) }
            }
        }.toByteArray()
        replaceAtomic(file, bytes); receipts = next
    }
}
