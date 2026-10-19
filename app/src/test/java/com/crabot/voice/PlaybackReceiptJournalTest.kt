package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class PlaybackReceiptJournalTest {
    @Test fun readsOldIntReceiptsAndWritesLongWithVerifiedBackup() {
        val dir = Files.createTempDirectory("voice-receipts-v1").toFile()
        try {
            val file = java.io.File(dir, "receipts"); val scope = "a".repeat(64); val id = UUID.randomUUID().toString()
            java.io.DataOutputStream(file.outputStream()).use { out ->
                out.writeInt(1); out.writeInt(1); out.writeUTF(scope); out.writeUTF(id); out.writeUTF("completed"); out.writeInt(480)
            }
            val before = file.readBytes()
            val store = PlaybackReceiptJournal(file)
            assertEquals(480L, store.pending(scope).single().played)
            assertArrayEquals(before, java.io.File(dir, "receipts.v1.bak").readBytes())
            store.remember(PlaybackReceipt(scope, UUID.randomUUID().toString(), "completed", 3_000_000_000L))
            assertEquals(3_000_000_000L, PlaybackReceiptJournal(file).pending(scope).last().played)
        } finally { dir.deleteRecursively() }
    }
    @Test fun retainsActualCompletionUntilTheOriginalReceiptIsConfirmed() {
        val dir = Files.createTempDirectory("voice-receipts").toFile()
        try {
            val file = java.io.File(dir, "receipts"); val scope = "a".repeat(64)
            val item = PlaybackReceipt(scope, UUID.randomUUID().toString(), "completed", 480)
            PlaybackReceiptJournal(file).remember(item)
            val restored = PlaybackReceiptJournal(file)
            assertEquals(listOf(item), restored.pending(scope)); assertTrue(restored.pending("b".repeat(64)).isEmpty())
            restored.remember(item); assertEquals(1, restored.pending(scope).size)
            restored.confirm("b".repeat(64), item.id); assertEquals(1, restored.pending(scope).size)
            restored.confirm(scope, item.id); assertTrue(PlaybackReceiptJournal(file).pending(scope).isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun neverManufacturesSuccessFromMissingSamplesOrConflictingFacts() {
        val dir = Files.createTempDirectory("voice-receipts").toFile()
        try {
            val store = PlaybackReceiptJournal(java.io.File(dir, "receipts")); val scope = "a".repeat(64); val id = UUID.randomUUID().toString()
            assertThrows(IllegalArgumentException::class.java) { store.remember(PlaybackReceipt(scope, id, "completed", null)) }
            store.remember(PlaybackReceipt(scope, id, "interrupted", 120))
            assertThrows(IllegalArgumentException::class.java) { store.remember(PlaybackReceipt(scope, id, "completed", 480)) }
            assertEquals("interrupted", store.pending(scope).single().status)
        } finally { dir.deleteRecursively() }
    }
}
