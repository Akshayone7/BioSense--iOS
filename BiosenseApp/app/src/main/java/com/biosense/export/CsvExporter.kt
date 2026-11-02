package com.biosense.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.biosense.ble.PacketParser
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object CsvExporter {

    /** Returns the display filename saved to the Downloads folder. */
    fun saveSession(context: Context, samples: List<Timestamped>, filename: String): String {
        val header = "t_ms,ax,ay,az,gx,gy,gz,ir,red\n"
        val body = buildString(samples.size * 40) {
            for (s in samples) {
                append(s.tMs); append(',')
                append(s.sample.ax); append(','); append(s.sample.ay); append(','); append(s.sample.az); append(',')
                append(s.sample.gx); append(','); append(s.sample.gy); append(','); append(s.sample.gz); append(',')
                append(s.sample.ir); append(','); append(s.sample.red); append('\n')
            }
        }
        val bytes = (header + body).toByteArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Biosense")
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
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Biosense")
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { it.write(bytes) }
        }
        return filename
    }

    data class Timestamped(val tMs: Long, val sample: PacketParser.Sample)
}
