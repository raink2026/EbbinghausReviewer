package com.ebbinghaus.review.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.ebbinghaus.review.utils.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class ImageRepository(private val context: Context) {

    @Throws(IOException::class)
    suspend fun saveImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver

        // Ensure directory exists
        val directory = File(context.filesDir, AppConstants.IMAGE_DIR)
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw IOException("Failed to create image directory")
            }
        }

        // Get extension
        val mime = contentResolver.getType(uri)
        val extension = if (mime != null) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) else "jpg"
        val ext = extension ?: "jpg"

        val fileName = "img_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext"
        val file = File(directory, fileName)

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Could not open input stream for URI: $uri")

            // Verify file
            if (file.exists() && file.length() > 0) {
                return@withContext Uri.fromFile(file).toString()
            } else {
                if (file.exists()) file.delete()
                throw IOException("File created but is empty or invalid")
            }
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            throw IOException("Failed to copy image to internal storage", e)
        }
    }
}
