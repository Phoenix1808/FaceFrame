package com.example.faceframe.collage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Puts the collage in the gallery, and hands it to the share sheet.
 *
 * CollageRenderer makes pixels; this turns them into files. Keeping the two
 * apart is what lets the renderer stay Context-free.
 */
class MediaSaver(private val context: Context) {

    /**
     * Saves into Pictures/FaceFrame.
     *
     * Two different worlds either side of Android 10. From Q you name a
     * relative path and MediaStore decides where it really goes; IS_PENDING
     * hides the row until the PNG is fully written, so nobody opens a
     * half-decoded image. Before Q you hand MediaStore a real file path, and
     * writing there needs WRITE_EXTERNAL_STORAGE.
     */
    fun saveToGallery(bitmap: Bitmap, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM"
            )
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val album = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM
            )
            if (!album.exists()) album.mkdirs()
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.DATA, File(album, "$displayName.png").absolutePath)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    fun shareIntent(bitmap: Bitmap, displayName: String): Intent {
        val folder = File(context.cacheDir, "collages").apply { mkdirs() }
        val file = File(folder, "$displayName.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val MIME_TYPE = "image/png"
        const val ALBUM = "FaceFrame"
    }
}
