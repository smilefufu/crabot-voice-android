package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class PlaybackReceiptJournalTest {
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
