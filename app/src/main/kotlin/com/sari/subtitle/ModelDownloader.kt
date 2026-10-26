package com.sari.subtitle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads a Vosk model to a staging directory, validates the extracted model, then promotes it.
 * The existing model is not touched until the new archive has downloaded and passed validation.
 */
object ModelDownloader {
    suspend fun downloadAndExtract(context: Context, url: String, outputDirectory: File): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://", ignoreCase = true)) { "رابط النموذج يجب أن يستخدم HTTPS." }
        val parent = outputDirectory.absoluteFile.parentFile ?: context.filesDir
        check(parent.exists() || parent.mkdirs()) { "تعذر تجهيز مجلد نماذج Vosk." }
        val staging = File(parent, "${outputDirectory.name}.staging-${System.currentTimeMillis()}")
        val archive = File(context.cacheDir, "vosk-model-${System.currentTimeMillis()}.zip")
        var connection: HttpURLConnection? = null
        try {
            staging.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            connection = conn
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) error("فشل تنزيل النموذج: HTTP $code")
            conn.inputStream.use { input -> archive.outputStream().buffered().use { output -> input.copyTo(output) } }
            check(archive.length() > 1024L) { "ملف النموذج الذي تم تنزيله فارغ أو غير مكتمل." }

            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var count = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, entry.name).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "الأرشيف يحتوي على مسار غير آمن." }
                    if (entry.isDirectory) {
                        check(target.mkdirs() || target.isDirectory) { "تعذر إنشاء مجلد داخل نموذج Vosk." }
                    } else {
                        target.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "تعذر تجهيز ملفات نموذج Vosk." } }
                        target.outputStream().buffered().use { out -> zip.copyTo(out) }
                        count++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                check(count > 0) { "ملف التنزيل ليس أرشيف نموذج صالحًا." }
            }

            // Official archives commonly contain a single top-level model directory.
            val children = staging.listFiles()?.filter { it.isDirectory }.orEmpty()
            val extractedModel = if (children.size == 1 && children[0].listFiles()?.isNotEmpty() == true) children[0] else staging
            check(File(extractedModel, "am").isDirectory && File(extractedModel, "conf").isDirectory) {
                "تم تنزيل الأرشيف، لكنه لا يحتوي بنية نموذج Vosk صالحة (am/ و conf/)."
            }

            // Keep the archive's model directory nested inside the models root; TranslationRepository
            // discovers a child directory containing the Vosk `am/` model files.
            val promotion = staging
            val backup = File(parent, "${outputDirectory.name}.previous-${System.currentTimeMillis()}")
            val hadOld = outputDirectory.exists()
            if (hadOld) check(outputDirectory.renameTo(backup)) { "تعذر حفظ النموذج السابق بأمان." }
            if (!promotion.renameTo(outputDirectory)) {
                if (hadOld) backup.renameTo(outputDirectory)
                error("تعذر تثبيت النموذج الجديد؛ تمت استعادة النموذج السابق إن كان موجودًا.")
            }
            if (hadOld) backup.deleteRecursively()
            outputDirectory
        } finally {
            connection?.disconnect()
            archive.delete()
            staging.deleteRecursively()
        }
    }
}
