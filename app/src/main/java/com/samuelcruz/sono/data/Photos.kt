package com.samuelcruz.sono.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Photos (IOUs, chat screenshots): shrunk to at most 1600 px and saved as JPEG (quality 75, usually 150–300 KB) in
 * filesDir/photos. Only the file name goes in the database.
 */
object Photos {
    private const val MAX_SIDE = 1600

    fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }
    fun file(context: Context, name: String) = File(dir(context), name)

    /** Reads, rotates upright, shrinks and saves the picture; returns the new file name. */
    suspend fun save(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot read the picture")
        val rotation = resolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val name = UUID.randomUUID().toString() + ".jpg"
        file(context, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        name
    }

    /** Loads a photo for display, shrunk to about [maxSide] px. */
    suspend fun load(context: Context, name: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(context, name)
        if (!f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Deletes photos no debt points to (left behind by a cancelled edit). */
    suspend fun cleanUp(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
        val used = db.debts().photos().toSet()
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L // keep fresh files: an edit may be in progress
        dir(context).listFiles()?.forEach { f -> if (f.name !in used && f.lastModified() < cutoff) f.delete() }
    }
}
