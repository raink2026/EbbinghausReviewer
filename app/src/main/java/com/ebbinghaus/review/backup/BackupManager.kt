package com.ebbinghaus.review.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.PlanItem
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.utils.AppConstants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    private val gson = Gson()
    private const val TAG = "BackupManager"

    // 导出数据到用户选定的 URI (ZIP文件)
    suspend fun exportData(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        val reviewDao = database.reviewDao()
        val planDao = database.planDao()

        val allReviewItems = reviewDao.getAllItemsSync()
        val allPlanItems = planDao.getAllPlansSync()

        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->

                    // 1. Create a list of items with modified image paths (but do not modify DB items)
                    // We need to write them to JSON stream one by one or as a list, but we first need to copy images.
                    // To stream efficiently, we probably want to iterate and write.
                    // However, we need to output the JSON structure properly.

                    // Since we are writing to a single ZIP entry for JSON, we open that entry first?
                    // No, usually we write images to ZIP entries, and the JSON file as another entry.
                    // The JSON refers to images by relative path in ZIP.

                    // Strategy:
                    // Loop through items, copy their images to ZIP, update the item object with new path,
                    // and collect these updated items to write to JSON.
                    // BUT, collecting all updated items in memory brings back the OOM risk if list is huge.
                    // So we must stream the JSON writing.

                    // We cannot write to JSON entry and Image entries simultaneously in standard ZipOutputStream.
                    // Standard ZipOutputStream requires putNextEntry -> write -> closeEntry.

                    // Workaround:
                    // 1. We can write images first. But we need to update the item references.
                    //    We can maintain the stream of updated items.
                    //    If the list of items itself is too big for memory, we are in trouble anyway unless we cursor it.
                    //    Dao.getAllItemsSync() loads all into memory.
                    //    For the purpose of this task (fix P1 OOM in Gson serialization), we assume List<ReviewItem> fits in memory,
                    //    but the JSON String generation + Byte Array caused the OOM.
                    //    So we iterate, copy images, create updated item object, add to a list (or better, yield it).

                    val updatedItems = allReviewItems.map { item ->
                        val newImagePaths = mutableListOf<String>()
                        if (!item.imagePaths.isNullOrEmpty()) {
                            item.imagePaths.split("|").forEachIndexed { index, pathUriString ->
                                if (pathUriString.isNotBlank()) {
                                    try {
                                        val sourceUri = Uri.parse(pathUriString)
                                        var inputStream: InputStream? = null
                                        
                                        // 根据 Scheme 选择读取方式
                                        if (sourceUri.scheme == "content") {
                                            try {
                                                inputStream = context.contentResolver.openInputStream(sourceUri)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Failed to open content URI: $pathUriString", e)
                                            }
                                        } else if (sourceUri.scheme == "file") {
                                            val path = sourceUri.path
                                            if (path != null) {
                                                val file = File(path)
                                                if (file.exists()) {
                                                    inputStream = FileInputStream(file)
                                                } else {
                                                    Log.w(TAG, "File not found: $path")
                                                }
                                            }
                                        } else {
                                            // 尝试直接作为路径处理
                                            val file = File(pathUriString)
                                            if (file.exists()) {
                                                inputStream = FileInputStream(file)
                                            }
                                        }

                                        if (inputStream != null) {
                                            inputStream.use { input ->
                                                val extension = getExtension(context, sourceUri, pathUriString) ?: "jpg"
                                                // 在 ZIP 中的相对路径
                                                val zipImageName = "${AppConstants.BACKUP_IMAGES_DIR}/${item.id}_${index}.$extension"
                                                
                                                zipOut.putNextEntry(ZipEntry(zipImageName))
                                                input.copyTo(zipOut)
                                                zipOut.closeEntry()
                                                newImagePaths.add(zipImageName)
                                            }
                                        } else {
                                            Log.e(TAG, "Could not open stream for image: $pathUriString")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error exporting image: $pathUriString", e)
                                    }
                                }
                            }
                        }
                        // 返回修改了图片路径的副本 (指向 ZIP 内的相对路径)
                        item.copy(imagePaths = if (newImagePaths.isEmpty()) null else newImagePaths.joinToString("|"))
                    }

                    // 2. 写入 JSON 数据 using Streaming to avoid OOM
                    zipOut.putNextEntry(ZipEntry(AppConstants.BACKUP_JSON_FILENAME))
                    val writer = OutputStreamWriter(zipOut)
                    gson.toJson(updatedItems, object : TypeToken<List<ReviewItem>>() {}.type, writer)
                    writer.flush() // Flush but do not close writer, as it would close zipOut
                    zipOut.closeEntry()

                    // 3. 写入 Plans 数据
                    if (allPlanItems.isNotEmpty()) {
                        zipOut.putNextEntry(ZipEntry(AppConstants.BACKUP_PLANS_FILENAME))
                        val planWriter = OutputStreamWriter(zipOut)
                        gson.toJson(allPlanItems, object : TypeToken<List<PlanItem>>() {}.type, planWriter)
                        planWriter.flush()
                        zipOut.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    // 从用户选定的 URI (ZIP文件) 导入数据
    suspend fun importData(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // 准备解压图片的目标目录 (app私有目录)
            val importDir = File(context.filesDir, "imported_media")
            if (!importDir.exists()) {
                importDir.mkdirs()
            }

            var reviewJsonString: String? = null
            var plansJsonString: String? = null
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val fileName = entry.name
                        if (!entry.isDirectory) {
                            if (fileName == AppConstants.BACKUP_JSON_FILENAME || fileName.endsWith(AppConstants.BACKUP_JSON_FILENAME)) {
                                reviewJsonString = InputStreamReader(zipIn).readText()
                            } else if (fileName == AppConstants.BACKUP_PLANS_FILENAME || fileName.endsWith(AppConstants.BACKUP_PLANS_FILENAME)) {
                                plansJsonString = InputStreamReader(zipIn).readText()
                            } else if (fileName.contains(AppConstants.BACKUP_IMAGES_DIR)) {
                                // 解压图片，扁平化存入 imported_media
                                val simpleFileName = File(fileName).name
                                val outFile = File(importDir, simpleFileName)
                                
                                FileOutputStream(outFile).use { fileOut ->
                                    zipIn.copyTo(fileOut)
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            val database = AppDatabase.getDatabase(context)
            var success = false

            if (reviewJsonString != null) {
                val listType = object : TypeToken<List<ReviewItem>>() {}.type
                val importedItems: List<ReviewItem> = gson.fromJson(reviewJsonString, listType)

                // 修正图片路径为本地绝对路径
                val finalItems = importedItems.map { item ->
                    val fixedPaths = if (!item.imagePaths.isNullOrEmpty()) {
                        item.imagePaths.split("|").mapNotNull { path ->
                            // 检查路径是否包含 images 目录特征
                            if (path.contains(AppConstants.BACKUP_IMAGES_DIR)) {
                                val fileName = File(path).name
                                val localFile = File(importDir, fileName)
                                if (localFile.exists()) {
                                    Uri.fromFile(localFile).toString() // file:///...
                                } else {
                                    null // 图片未找到
                                }
                            } else {
                                path // 兼容旧数据或非打包图片
                            }
                        }.joinToString("|")
                    } else null
                    
                    item.copy(imagePaths = if (fixedPaths.isNullOrBlank()) null else fixedPaths)
                }

                val dao = database.reviewDao()
                
                // 导入策略：全量覆盖
                dao.deleteAll()
                dao.insertAll(finalItems) 
                success = true
            }
            
            if (plansJsonString != null) {
                try {
                    val listType = object : TypeToken<List<PlanItem>>() {}.type
                    val importedPlans: List<PlanItem> = gson.fromJson(plansJsonString, listType)

                    val planDao = database.planDao()
                    planDao.deleteAll()
                    planDao.insertAll(importedPlans)
                    success = true // 如果只有 plans 也可以算成功
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import plans", e)
                    // Don't fail the whole import if plans fail, but log it
                }
            }

            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            false
        }
    }

    private fun getExtension(context: Context, uri: Uri, originalPath: String): String? {
        try {
            // 1. Try from MimeType (ContentProvider)
            if (uri.scheme == "content") {
                val mime = context.contentResolver.getType(uri)
                if (mime != null) {
                    return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                }
            }
            
            // 2. Try from Path String (File extension)
            val fileExtension = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(originalPath))
            if (!fileExtension.isNullOrBlank()) return fileExtension

            // 3. Manual fallback
            val lastDot = originalPath.lastIndexOf('.')
            if (lastDot != -1 && lastDot < originalPath.length - 1) {
                return originalPath.substring(lastDot + 1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
