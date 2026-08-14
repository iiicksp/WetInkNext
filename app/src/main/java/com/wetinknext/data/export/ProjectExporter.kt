package com.wetinknext.data.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wetinknext.engine.export.ExportSnapshot
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Encodes an [ExportSnapshot] to PNG / JPEG / PSD and publishes it to the
 * system media store. Runs off the GL thread — the snapshot is already an
 * immutable byte copy, so encoding is pure CPU work.
 */
object ProjectExporter {

    private const val QUALITY_JPEG = 92
    private const val SUBDIRECTORY = "WetInkNext"

    suspend fun exportPng(context: Context, snapshot: ExportSnapshot): String {
        val png = runCatching {
            val bytes = java.io.ByteArrayOutputStream()
            toBitmap(snapshot.composite, snapshot.width, snapshot.height)
                .compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bytes.toByteArray()
        }.getOrElse { throw ExportException("Не удалось закодировать PNG", it) }
        return saveToMedia(
            context = context,
            fileName = fileName(snapshot.documentName, "png"),
            mimeType = "image/png",
            directory = Environment.DIRECTORY_PICTURES,
            bytes = png,
        )
    }

    /**
     * JPEG has no alpha channel, so the transparent canvas is flattened onto
     * the user-visible canvas backdrop colour first — mirrors what the canvas
     * looks like in the editor.
     */
    suspend fun exportJpeg(context: Context, snapshot: ExportSnapshot, backgroundColorArgb: Int): String {
        val jpeg = runCatching {
            val source = toBitmap(snapshot.composite, snapshot.width, snapshot.height)
            val flattened = Bitmap.createBitmap(snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(backgroundColorArgb)
                drawBitmap(source, 0f, 0f, null)
            }
            val bytes = java.io.ByteArrayOutputStream()
            flattened.compress(Bitmap.CompressFormat.JPEG, QUALITY_JPEG, bytes)
            bytes.toByteArray()
        }.getOrElse { throw ExportException("Не удалось закодировать JPEG", it) }
        return saveToMedia(
            context = context,
            fileName = fileName(snapshot.documentName, "jpg"),
            mimeType = "image/jpeg",
            directory = Environment.DIRECTORY_PICTURES,
            bytes = jpeg,
        )
    }

    suspend fun exportPsd(context: Context, snapshot: ExportSnapshot): String {
        val psd = runCatching { PsdWriter.write(snapshot) }
            .getOrElse { throw ExportException("Не удалось собрать PSD", it) }
        return saveToMedia(
            context = context,
            fileName = fileName(snapshot.documentName, "psd"),
            mimeType = "application/photoshop",
            directory = Environment.DIRECTORY_DOWNLOADS,
            bytes = psd,
        )
    }

    private fun toBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return bitmap
    }

    /**
     * MediaStore on Android 10+, app-external files on older versions (no
     * storage permission needed either way). Returns a human-readable location.
     */
    private fun saveToMedia(
        context: Context,
        fileName: String,
        mimeType: String,
        directory: String,
        bytes: ByteArray,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$SUBDIRECTORY")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("MediaStore refused an entry for $fileName")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("Cannot open output stream for $fileName")
            } finally {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return "$directory/$SUBDIRECTORY/$fileName"
        }

        // Android 8-9: no permissions in this app build, so publish into the
        // app-external directory (visible to file managers, no gallery scan).
        val folder = File(context.getExternalFilesDir(directory), SUBDIRECTORY)
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("Cannot create export directory ${folder.path}")
        }
        val file = File(folder, fileName)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun fileName(documentName: String, extension: String): String {
        val base = documentName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .take(80)
            .ifBlank { "WetInk" }
        return "$base.$extension"
    }

    private class ExportException(message: String, cause: Throwable) : IOException(message, cause)
}