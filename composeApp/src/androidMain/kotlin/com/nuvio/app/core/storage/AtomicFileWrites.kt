package com.nuvio.app.core.storage

import android.os.Build
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Verify AtomicFile's commit, which can silently leave the .new file on some file systems. */
internal fun AtomicFile.finishWriteChecked(stream: FileOutputStream) {
    finishWrite(stream)
    val pending = File(baseFile.path + ".new")
    if (pending.exists()) replaceFile(pending, baseFile)
}

/** Replace an existing file without first deleting the last valid copy. */
internal fun replaceFile(source: File, target: File) {
    if (Build.VERSION.SDK_INT >= 26) {
        Api26.replace(source, target)
    } else if (!source.renameTo(target)) {
        throw IOException("Could not replace ${target.name}")
    }
}

@RequiresApi(26)
private object Api26 {
    fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
