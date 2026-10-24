package com.sari.subtitle.v2

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Manages optional offline Vosk models. Models are downloaded only after explicit user action. */
object VoskModelManager {
    data class ModelInfo(val id: String, val title: String, val url: String, val approximateSize: String)

    // Official Vosk model catalog. Arabic model supports Modern Standard Arabic; not specifically Yemeni dialect.
    val ARABIC = ModelInfo(
        "vosk-model-ar-mgb2-0.4",
        "العربية (318 ميغابايت تقريباً)",
        "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
        "318 MB"
    )
    val ENGLISH = ModelInfo(
        "vosk-model-small-en-us-0.15",
        "الإنجليزية (40 ميغابايت تقريباً)",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        "40 MB"
    )

    fun modelDirectory(context: Context, model: ModelInfo): File = File(File(context.filesDir, "vosk-models"), model.id)
    fun isInstalled(context: Context, model: ModelInfo): Boolean {
        val dir = modelDirectory(context, model)
        return File(dir, "am/final.mdl").exists()
    }

    fun installedModel(context: Context, language: String): File? {
        val lang = language.trim().lowercase(java.util.Locale.ROOT)
        val arabicInstalled = isInstalled(context, ARABIC)
        val englishInstalled = isInstalled(context, ENGLISH)

        // Explicit language always prefers its matching model.
        if (lang.startsWith("en")) {
            return if (englishInstalled) modelDirectory(context, ENGLISH) else null
        }
        if (lang.startsWith("ar")) {
            return if (arabicInstalled) modelDirectory(context, ARABIC) else null
        }

        // For auto/blank language, use the only installed model instead of
        // assuming Arabic. This fixes the case where Settings reports the
        // English model installed but the transcriber was looking only for Arabic.
        // If both exist, keep Arabic as the deterministic default; callers that
        // know the source language should pass "en" or "ar" explicitly.
        return when {
            arabicInstalled && !englishInstalled -> modelDirectory(context, ARABIC)
            englishInstalled && !arabicInstalled -> modelDirectory(context, ENGLISH)
            arabicInstalled && englishInstalled -> modelDirectory(context, ARABIC)
            else -> null
        }
    }

    /** Blocking; caller must run this off the main thread. Downloads and extracts atomically. */
    fun install(context: Context, model: ModelInfo, progress: (String) -> Unit) {
        val target = modelDirectory(context, model)
        if (isInstalled(context, model)) { progress("النموذج مثبت مسبقاً"); return }
        val root = target.parentFile ?: throw IllegalStateException("مسار النموذج غير صالح")
        if (!root.exists() && !root.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد النماذج")
        val zip = File(context.cacheDir, "${model.id}.zip.part")
        val partial = File(context.cacheDir, "${model.id}.extracting")
        zip.delete(); deleteRecursively(partial)
        var connection: HttpURLConnection? = null
        try {
            connection = NetworkRequestRegistry.register(URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 45000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SariSubtitle-Android")
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("تعذر تنزيل النموذج (HTTP $code)")
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(zip)).use { output ->
                    val buffer = ByteArray(64 * 1024); var read: Int; var received = 0L; var lastPercent = -1
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        output.write(buffer, 0, read); received += read
                        if (total > 0) {
                            val percent = ((received * 100) / total).toInt()
                            if (percent != lastPercent) { lastPercent = percent; progress("تنزيل النموذج: $percent%") }
                        } else if (received % (5L * 1024 * 1024) < buffer.size) progress("تم تنزيل ${received / (1024 * 1024)} ميغابايت…")
                    }
                }
            }
            if (!partial.mkdirs()) throw IllegalStateException("تعذر تجهيز ملفات النموذج")
            ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val clean = entry.name.replace('\\', '/')
                    if (clean.startsWith("/") || clean.split('/').any { it == ".." }) throw IllegalStateException("ملف نموذج غير آمن")
                    val parts = clean.split('/').filter { it.isNotBlank() }
                    // Official archives contain one top-level directory; remove that prefix.
                    val relative = if (parts.size > 1) parts.drop(1).joinToString("/") else ""
                    if (relative.isNotBlank()) {
                        val out = File(partial, relative).canonicalFile
                        if (!out.path.startsWith(partial.canonicalPath + File.separator)) throw IllegalStateException("مسار استخراج غير آمن")
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos -> var n: Int; while (zis.read(buffer).also { n = it } >= 0) { if (n > 0) fos.write(buffer, 0, n) } }
                        }
                    }
                    zis.closeEntry()
                }
            }
            val valid = File(partial, "am/final.mdl").exists()
            if (!valid) throw IllegalStateException("ملف النموذج نزل لكن بنيته غير مكتملة")
            deleteRecursively(target)
            if (!partial.renameTo(target)) throw IllegalStateException("تعذر تثبيت ملفات النموذج")
            progress("اكتمل تثبيت النموذج: ${model.title}")
        } finally {
            connection?.disconnect(); connection?.let { NetworkRequestRegistry.unregister(it) }; zip.delete(); deleteRecursively(partial)
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}
