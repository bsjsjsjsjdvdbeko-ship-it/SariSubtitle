package com.sari.subtitle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads a user-selected Vosk model archive; it never hardcodes a model URL or claims a model is installed. */
object ModelDownloader {
    suspend fun downloadAndExtract(context: Context, url: String, outputDirectory: File): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "رابط النموذج يجب أن يستخدم HTTPS." }
        outputDirectory.mkdirs()
        val temp = File(outputDirectory.parentFile ?: context.cacheDir, "vosk-model-download.tmp")
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000; requestMethod = "GET"
            }
            connection.connect()
            if (connection.responseCode !in 200..299) error("فشل تنزيل النموذج: HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            ZipInputStream(temp.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(outputDirectory, entry.name).canonicalFile
                    require(target.path.startsWith(outputDirectory.canonicalPath + File.separator)) { "مسار نموذج غير آمن." }
                    if (entry.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                    entry = zip.nextEntry
                }
            }
            outputDirectory
        } finally { temp.delete() }
    }
}
