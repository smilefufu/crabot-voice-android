package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class AtomicStorageTest {
    @Test fun commitsCompleteBytesAndRemovesTemporaryFile() {
        val directory = Files.createTempDirectory("voice-store-test").toFile()
        try {
            val file = java.io.File(directory, "terminal.enc")
            file.writeBytes(byteArrayOf(1, 2))
            replaceAtomic(file, byteArrayOf(3, 4, 5))
            assertArrayEquals(byteArrayOf(3, 4, 5), file.readBytes())
            assertEquals(listOf("terminal.enc"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedWritePreservesOriginalFile() {
        val directory = Files.createTempDirectory("voice-store-test").toFile()
        val permissions = Files.getPosixFilePermissions(directory.toPath())
        try {
            val file = java.io.File(directory, "terminal.enc")
            file.writeBytes(byteArrayOf(1, 2))
            Files.setPosixFilePermissions(directory.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
            assertThrows(java.io.IOException::class.java) { replaceAtomic(file, byteArrayOf(3, 4)) }
            assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
        } finally { Files.setPosixFilePermissions(directory.toPath(), permissions); directory.deleteRecursively() }
    }
    @Test fun failedRenameLeavesExistingTargetAndCleansTemporaryFile() {
        val directory = Files.createTempDirectory("voice-store-test").toFile()
        try {
            val target = java.io.File(directory, "terminal.enc").apply { mkdir() }
            val previous = java.io.File(target, "previous").apply { writeBytes(byteArrayOf(1, 2)) }
            assertThrows(java.io.IOException::class.java) { replaceAtomic(target, byteArrayOf(3, 4)) }
            assertArrayEquals(byteArrayOf(1, 2), previous.readBytes())
            assertEquals(listOf("terminal.enc"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }
}
