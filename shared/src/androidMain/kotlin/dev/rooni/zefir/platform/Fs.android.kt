package dev.rooni.zefir.platform

import java.io.File

actual object Fs {
    actual fun readText(path: String) = File(path).readText()
    actual fun writeText(path: String, text: String) {
        val target = File(path)
        target.parentFile?.mkdirs()

        val draft = File("$path.tmp")
        draft.writeText(text)
        if (!draft.renameTo(target)) {
            target.delete()
            draft.renameTo(target)
        }
    }
    actual fun appendText(path: String, text: String) {
        File(path).parentFile?.mkdirs()
        File(path).appendText(text)
    }
    actual fun delete(path: String) { File(path).delete() }
    actual fun exists(path: String) = File(path).exists()
    actual fun mkdirs(path: String) { File(path).mkdirs() }
}

actual fun nowMillis() = System.currentTimeMillis()
