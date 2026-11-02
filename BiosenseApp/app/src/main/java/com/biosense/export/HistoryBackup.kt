package com.biosense.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * User-facing backup for the persisted history folder. The internal
 * `<filesDir>/history/` gets wiped when the app is uninstalled or has its
 * data cleared — Android's auto-backup rules cover the cloud case, but if
 * the user isn't signed in (or the developer is sideloading fresh APKs) the
 * data is gone. This utility packs the whole history directory into a ZIP
 * under `Downloads/Biosense/` so users can save it themselves, and
 * unpacks it back.
 */
object HistoryBackup {

    private const val SUBDIR = "Biosense"

    /** Returns the display filename saved to Downloads. */
    fun exportZip(context: Context, filename: String): String {
        val historyDir = File(context.filesDir, "history")
        require(historyDir.exists() && (historyDir.listFiles()?.isNotEmpty() == true)) {
            "no history to back up"
        }

        val bytes = java.io.ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                historyDir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            baos.toByteArray()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused Downloads insert")
            resolver.openOutputStream(uri).use { os: OutputStream? ->
                requireNotNull(os).write(bytes)
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { it.write(bytes) }
        }
        return filename
    }

    /**
     * Restore a previously exported ZIP. Merges into the existing history dir
     * (does not clear). Returns number of files restored.
     */
    fun importZip(context: Context, uri: Uri): Int {
        val historyDir = File(context.filesDir, "history").also { if (!it.exists()) it.mkdirs() }
        var count = 0
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open backup uri" }
            ZipInputStream(input).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                val buf = ByteArray(8192)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name // guard path traversal
                        val out = File(historyDir, name)
                        FileOutputStream(out).use { fo ->
                            var n = zin.read(buf)
                            while (n > 0) { fo.write(buf, 0, n); n = zin.read(buf) }
                        }
                        count++
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        return count
    }
}
