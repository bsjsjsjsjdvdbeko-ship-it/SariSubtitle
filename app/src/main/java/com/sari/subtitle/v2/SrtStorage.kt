package com.sari.subtitle.v2

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class SavedSrtItem(val id: String, val title: String, val cueCount: Int, val savedAt: Long, val sourceUrl: String? = null)

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
    private val libraryIndexFile: File by lazy { File(dir, "library-index.json") }

    /** حفظ ملف SRT مستقل في مكتبة التطبيق حتى لو لم يكن مرتبطاً برابط فيديو. */
    fun saveLibraryItem(title: String, srtText: String, cueCount: Int, sourceUrl: String? = null): String? {
        return try {
            val id = UUID.randomUUID().toString()
            val fileName = "library_$id.srt"
            File(dir, fileName).writeText(srtText, Charsets.UTF_8)
            val index = readLibraryIndex()
            index.put(id, JSONObject().apply {
                put("title", title.ifBlank { "ترجمة محفوظة.srt" })
                put("fileName", fileName)
                put("cueCount", cueCount)
                put("savedAt", System.currentTimeMillis())
                put("sourceUrl", sourceUrl?.trim().orEmpty())
            })
            writeLibraryIndex(index)
            id
        } catch (_: Exception) { null }
    }

    fun listLibraryItems(): List<SavedSrtItem> {
        return try {
            val index = readLibraryIndex()
            val items = mutableListOf<SavedSrtItem>()
            val keys = index.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val entry = index.optJSONObject(id) ?: continue
                val fileName = entry.optString("fileName")
                if (fileName.isBlank() || !File(dir, fileName).isFile) continue
                items += SavedSrtItem(id, entry.optString("title", "ترجمة محفوظة.srt"), entry.optInt("cueCount"), entry.optLong("savedAt"), entry.optString("sourceUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") })
            }
            items.sortedByDescending { it.savedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun loadLibraryItem(id: String): String? {
        return try {
            val entry = readLibraryIndex().optJSONObject(id) ?: return null
            val name = entry.optString("fileName")
            if (name.isBlank()) null else File(dir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun deleteLibraryItem(id: String): Boolean {
        return try {
            val index = readLibraryIndex()
            val entry = index.optJSONObject(id) ?: return false
            val name = entry.optString("fileName")
            if (name.isNotBlank()) File(dir, name).delete()
            index.remove(id)
            writeLibraryIndex(index)
            true
        } catch (_: Exception) { false }
    }

    /**
     * يحفظ SRT مرتبطاً بـ URL.
     * @return اسم الملف المحفوظ، أو null عند الفشل.
     */
    fun save(url: String, srtText: String, cueCount: Int): String? {
        return try {
            val normalizedUrl = normalizeUrl(url)
            if (normalizedUrl.isBlank() || srtText.isBlank()) return null
            val hash = hashUrl(url)
            val fileName = "$hash.srt"
            val file = File(dir, fileName)
            val tempFile = File(dir, "$fileName.tmp")
            tempFile.writeText(srtText, Charsets.UTF_8)
            if (!tempFile.isFile || tempFile.length() <= 0L) {
                tempFile.delete()
                return null
            }
            if (file.exists() && !file.delete()) {
                tempFile.delete()
                return null
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            if (!file.isFile || file.length() <= 0L) return null

            // Persist the index only after the SRT file has been written and verified.
            val index = readIndex()
            index.put(hash, JSONObject().apply {
                put("url", url.trim())
                put("normalizedUrl", normalizedUrl)
                put("canonicalKey", canonicalKey(url))
                put("fileName", fileName)
                put("cueCount", cueCount)
                put("savedAt", System.currentTimeMillis())
            })
            if (!writeIndex(index)) return null
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
            val cleaned = raw.trim().trimEnd('.', ',', ')', ']', '}', '،')
            val uri = Uri.parse(cleaned)
            var host = (uri.host ?: "").lowercase(java.util.Locale.ROOT)
            if (host.startsWith("www.")) host = host.removePrefix("www.")
            val path = uri.path.orEmpty().trimEnd('/')

            // X/Twitter: status ID is the stable identity; ignore scheme, username,
            // www/mobile host and every query parameter such as ?s=20 or ?ref_src=.
            val status = Regex("(?:^|/)status/(\\d+)(?:/|$)")
                .find(path)?.groupValues?.getOrNull(1)
            val isXHost = host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com")
            if (isXHost && status != null) return "x-status:$status"

            val youtubeId = when {
                host == "youtu.be" -> path.removePrefix("/").substringBefore("/")
                host.contains("youtube.com") && path == "/watch" -> uri.getQueryParameter("v").orEmpty()
                host.contains("youtube.com") && path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
                else -> ""
            }
            if (youtubeId.isNotBlank()) return "youtube-id:$youtubeId"

            val allowed = uri.queryParameterNames.filterNot {
                it.equals("si", true) || it.equals("feature", true) || it.startsWith("utm_", true) ||
                    it.equals("fbclid", true) || it.equals("gclid", true)
            }.sorted().flatMap { key -> uri.getQueryParameters(key).map { key to it } }
            val query = allowed.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            buildString {
                append(uri.scheme?.lowercase(java.util.Locale.ROOT) ?: "https").append("://").append(host)
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
        val canonical = canonicalKey(url)
        val keys = index.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = index.optJSONObject(key) ?: continue
            val storedCanonical = entry.optString("canonicalKey")
            if (canonical.isNotBlank() && storedCanonical == canonical) return key to entry
            val storedNormalized = entry.optString("normalizedUrl").ifBlank { normalizeUrl(entry.optString("url")) }
            if (storedNormalized == normalized) return key to entry
        }
        return null
    }

    /** Stable key for platforms with a durable content ID (currently X/Twitter). */
    private fun canonicalKey(raw: String): String {
        val normalized = normalizeUrl(raw)
        return if (normalized.startsWith("x-status:")) normalized else ""
    }

    private fun hashUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(normalizeUrl(url).toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            normalizeUrl(url).hashCode().toString()
        }
    }

    private fun readLibraryIndex(): JSONObject {
        return try { if (libraryIndexFile.exists()) JSONObject(libraryIndexFile.readText(Charsets.UTF_8)) else JSONObject() }
        catch (_: Exception) { JSONObject() }
    }

    private fun writeLibraryIndex(index: JSONObject) {
        try { libraryIndexFile.writeText(index.toString(2), Charsets.UTF_8) } catch (_: Exception) {}
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

    private fun writeIndex(index: JSONObject): Boolean {
        return try {
            val temp = File(dir, "index.json.tmp")
            val content = index.toString(2)
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.isFile || temp.length() <= 0L) {
                temp.delete()
                return false
            }
            // Write a complete temporary index first; replace the old index only after serialization.
            if (!temp.renameTo(indexFile)) {
                temp.copyTo(indexFile, overwrite = true)
                temp.delete()
            }
            indexFile.isFile && indexFile.length() > 0L &&
                runCatching { JSONObject(indexFile.readText(Charsets.UTF_8)).length() == index.length() }.getOrDefault(false)
        } catch (_: Exception) { false }
    }
}
