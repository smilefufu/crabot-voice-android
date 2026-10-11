package com.crabot.voice

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Commit only after sync and atomic rename succeed; neither failure replaces the old file. */
internal fun replaceAtomic(file: File, data: ByteArray) {
    val temporary = File.createTempFile("terminal-", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { out -> out.write(data); out.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
