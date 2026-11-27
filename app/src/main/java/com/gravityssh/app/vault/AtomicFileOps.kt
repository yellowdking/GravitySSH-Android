package com.gravityssh.app.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object AtomicFileOps {
    fun writeBytesAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent ?: File("."), "${target.name}.${UUID.randomUUID()}.tmp")
        var moved = false
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                moved = true
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                moved = true
            } catch (_: UnsupportedOperationException) {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                moved = true
            }
        } finally {
            if (!moved && tmp.exists()) {
                runCatching { tmp.delete() }
            }
        }
    }
}
