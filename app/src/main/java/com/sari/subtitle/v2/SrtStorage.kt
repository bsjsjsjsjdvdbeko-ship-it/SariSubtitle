package com.sari.subtitle.v2

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * إدارة تخزين ملفات SRT محلياً، مع فهرس URL → ملف.
 *
 * المسار: /sdcard/Download/SariSubtitle/
 * الملفات:
 *   index.json    — فهرس { url_hash: { url, fileName, cueCount, savedAt } }
 *   <hash>.srt    — محتوى SRT
 */
class SrtStorage(private val context: Context) {

    private val dir: File by lazy {
        // نستخدم مجلد التطبيق الخاص (لا يحتاج صلاحيات)
        val d = File(context.filesDir, "srt")
        if (!d.exists()) d.mkdirs()
        d
    }

    private val indexFile: File by lazy { File(dir, "index.json") }

    /**
     * يحفظ SRT مرتبطاً بـ URL.
     * @return اسم الملف المحفوظ، أو null عند الفشل.
     */
    fun save(url: String, srtText: String, cueCount: Int): String? {
        return try {
            val hash = hashUrl(url)
            val fileName = "$hash.srt"
            val file = File(dir, fileName)
            file.writeText(srtText, Charsets.UTF_8)

            // تحديث الفهرس
            val index = readIndex()
            index.put(hash, JSONObject().apply {
                put("url", url)
                put("fileName", fileName)
                put("cueCount", cueCount)
                put("savedAt", System.currentTimeMillis())
            })
            writeIndex(index)
            fileName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يحمّل SRT مرتبطاً بـ URL.
     * @return نص SRT، أو null إن لم يوجد.
     */
    fun load(url: String): String? {
        return try {
            val index = readIndex()
            val entry = findEntry(index, url) ?: return null
            val fileName = entry.optString("fileName")
            if (fileName.isBlank()) return null
            val file = File(dir, fileName)
            if (!file.exists()) return null
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * هل يوجد SRT محفوظ لهذا URL؟
     */
    fun has(url: String): Boolean {
        val entry = findEntry(readIndex(), url) ?: return false
        val fileName = entry.optString("fileName")
        return fileName.isNotBlank() && File(dir, fileName).exists()
    }

    /**
     * يحذف SRT مرتبطاً بـ URL.
     */
    fun delete(url: String): Boolean {
        return try {
            val index = readIndex()
            val found = findEntryPair(index, url) ?: return false
            val (hash, entry) = found
            val fileName = entry.optString("fileName")
            File(dir, fileName).delete()
            index.remove(hash)
            writeIndex(index)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * عدد الملفات المحفوظة.
     */
    fun count(): Int {
        return try {
            readIndex().length()
        } catch (e: Exception) { 0 }
    }

    /**
     * يعيد قائمة URL المحفوظة.
     */
    fun listUrls(): List<String> {
        return try {
            val index = readIndex()
            val result = mutableListOf<String>()
            val keys = index.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val url = index.optJSONObject(k)?.optString("url") ?: continue
                result.add(url)
            }
            result
        } catch (e: Exception) { emptyList() }
    }

    /** Normalize common tracking/format differences so a reopened video can reuse its SRT. */
    private fun normalizeUrl(raw: String): String {
        return try {
            val uri = Uri.parse(raw.trim())
            val host = (uri.host ?: "").lowercase()
            val path = uri.path.orEmpty().trimEnd('/')
            // X/Twitter URLs for the same status are equivalent across x.com/twitter.com and mobile paths.
            val status = Regex("/(?:[^/]+/)?status/(\\d+)").find(path)?.groupValues?.getOrNull(1)
            if ((host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")) && status != null) {
                return "https://x.com/i/status/$status"
            }
            val youtubeId = when {
                host == "youtu.be" -> path.removePrefix("/").substringBefore("/")
                host.contains("youtube.com") && path == "/watch" -> uri.getQueryParameter("v").orEmpty()
                host.contains("youtube.com") && path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
                else -> ""
            }
            if (youtubeId.isNotBlank()) return "https://www.youtube.com/watch?v=$youtubeId"
            val allowed = uri.queryParameterNames.filterNot {
                it.equals("si", true) || it.equals("feature", true) || it.startsWith("utm_", true) ||
                    it.equals("fbclid", true) || it.equals("gclid", true)
            }.sorted().flatMap { key -> uri.getQueryParameters(key).map { key to it } }
            val query = allowed.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            buildString {
                append(uri.scheme?.lowercase() ?: "https").append("://").append(host)
                if (uri.port != -1) append(":").append(uri.port)
                append(path)
                if (query.isNotBlank()) append("?").append(query)
            }
        } catch (_: Exception) { raw.trim() }
    }

    private fun findEntry(index: JSONObject, url: String): JSONObject? = findEntryPair(index, url)?.second

    private fun findEntryPair(index: JSONObject, url: String): Pair<String, JSONObject>? {
        val directHash = hashUrl(url)
        index.optJSONObject(directHash)?.let { return directHash to it }
        val normalized = normalizeUrl(url)
        val keys = index.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = index.optJSONObject(key) ?: continue
            if (normalizeUrl(entry.optString("url")) == normalized) return key to entry
        }
        return null
    }

    private fun hashUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(normalizeUrl(url).toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }

    private fun readIndex(): JSONObject {
        return try {
            if (indexFile.exists()) {
                JSONObject(indexFile.readText(Charsets.UTF_8))
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeIndex(index: JSONObject) {
        try {
            indexFile.writeText(index.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {}
    }
}
