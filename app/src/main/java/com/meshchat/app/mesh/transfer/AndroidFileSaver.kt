package com.meshchat.app.mesh.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 接收完成的文件写入公共 Downloads：API 29+ 走 MediaStore（免权限）；API 26-28 走公共目录（需 WRITE_EXTERNAL_STORAGE）。 */
class AndroidFileSaver(private val context: Context) : FileSaver {
    override fun save(tmpFile: File, fileName: String, mime: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                tmpFile.inputStream().use { it.copyTo(out) }
            } != null
            if (!ok) { context.contentResolver.delete(uri, null, null); return null }
            return uri.toString()
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, fileName)
        return runCatching {
            tmpFile.copyTo(target, overwrite = true)
            Uri.fromFile(target).toString()
        }.getOrNull()
    }
}
