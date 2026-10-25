package com.sari.subtitle

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
 * المسار الفعلي: context.filesDir/srt (مجلد خاص بالتطبيق، لا يحتاج صلاحية تخزين عامة).
 * الملفات:
 *   index.json    — فهرس { url_hash: { url, fileName, cueCount, savedAt } }
 *   <hash>_<uuid>.srt — نسخة SRT محفوظة بإصدار مستقل لتفادي فقدان آخر نسخة عند فشل تحديث الفهرس.
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

    /**
     * يحفظ ملف SRT في المكتبة مع upsert حسب هوية الفيديو.
     * عند وجود سجل سابق لنفس منشور X (Status ID) أو لنفس مصدر الفيديو، يتم تحديثه
     * وإزالة السجلات المكررة بدلاً من إنشاء سجل جديد.
     */
    fun saveLibraryItem(title: String, srtText: String, cueCount: Int, sourceUrl: String? = null): String? {
        if (srtText.isBlank()) return null
        var createdFile: File? = null
        var tempFile: File? = null
        return try {
            val safeSource = sourceUrl?.trim()?.takeIf {
                (it.startsWith("https://", true) || it.startsWith("http://", true) ||
                    it.startsWith("content://", true) || it.startsWith("file://", true)) &&
                    !it.startsWith("local-media://", true)
            }
            val index = readLibraryIndex()
            val matchingIds = mutableListOf<String>()
            if (!safeSource.isNullOrBlank()) {
                val incomingCanonical = VideoUrlIdentity.canonicalKey(safeSource)
                val incomingNormalized = VideoUrlIdentity.normalize(safeSource)
                val keys = index.keys()
                while (keys.hasNext()) {
                    val existingId = keys.next()
                    val entry = index.optJSONObject(existingId) ?: continue
                    val existingSource = entry.optString("sourceUrl").takeIf { it.isNotBlank() } ?: continue
                    if (existingSource.startsWith("local-media://", true)) continue
                    val existingCanonical = VideoUrlIdentity.canonicalKey(existingSource)
                    val existingNormalized = VideoUrlIdentity.normalize(existingSource)
                    val same = if (incomingCanonical.isNotBlank()) {
                        incomingCanonical == existingCanonical
                    } else {
                        existingCanonical.isBlank() && incomingNormalized.isNotBlank() && incomingNormalized == existingNormalized
                    }
                    if (same) matchingIds += existingId
                }
            }

            // Reuse an existing item ID so the library entry is replaced in place.
            val id = matchingIds.firstOrNull() ?: UUID.randomUUID().toString()
            val fileName = "library_${UUID.randomUUID()}.srt"
            val outputFile = File(dir, fileName)
            createdFile = outputFile
            tempFile = File(dir, "$fileName.tmp")
            tempFile!!.writeText(srtText, Charsets.UTF_8)
            if (!tempFile!!.isFile || tempFile!!.length() <= 0L) return null
            if (!tempFile!!.renameTo(outputFile)) {
                tempFile!!.copyTo(outputFile, overwrite = false)
                tempFile!!.delete()
            }
            if (!outputFile.isFile || outputFile.length() <= 0L) return null

            val oldFiles = mutableListOf<String>()
            matchingIds.forEach { matchedId ->
                index.optJSONObject(matchedId)?.optString("fileName")?.takeIf { it.isNotBlank() && it != fileName }?.let(oldFiles::add)
                if (matchedId != id) index.remove(matchedId)
            }
            index.put(id, JSONObject().apply {
                put("title", title.ifBlank { "ترجمة محفوظة.srt" })
                put("fileName", fileName)
                put("cueCount", cueCount)
                put("savedAt", System.currentTimeMillis())
                if (safeSource != null) put("sourceUrl", safeSource)
                else {
                    // Keep a previous valid source if this update did not supply one.
                    val previousSource = readLibraryIndex().optJSONObject(id)?.optString("sourceUrl")
                    if (!previousSource.isNullOrBlank() && !previousSource.startsWith("local-media://", true)) put("sourceUrl", previousSource)
                }
            })
            if (!writeLibraryIndex(index)) {
                outputFile.delete()
                createdFile = null
                return null
            }

            // Delete previous payloads only after the new index has been committed.
            oldFiles.distinct().forEach { oldName -> if (oldName != fileName) safeSrtFile(oldName)?.delete() }
            createdFile = null
            id
        } catch (_: Exception) {
            null
        } finally {
            tempFile?.delete()
            createdFile?.delete()
        }
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
                val file = safeSrtFile(fileName) ?: continue
                if (!file.isFile) continue
                items += SavedSrtItem(id, entry.optString("title", "ترجمة محفوظة.srt"), entry.optInt("cueCount"), entry.optLong("savedAt"), entry.optString("sourceUrl").takeIf { it.isNotBlank() })
            }
            items.sortedByDescending { it.savedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun loadLibraryItem(id: String): String? {
        return try {
            val entry = readLibraryIndex().optJSONObject(id) ?: return null
            val name = entry.optString("fileName")
            safeSrtFile(name)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /** Repair source metadata for legacy library entries without rewriting the SRT payload. */
    fun updateLibraryItemSource(id: String, sourceUrl: String): Boolean {
        val clean = sourceUrl.trim()
        if (!(clean.startsWith("https://", true) || clean.startsWith("http://", true) ||
                clean.startsWith("content://", true) || clean.startsWith("file://", true))) return false
        return try {
            val index = readLibraryIndex()
            val entry = index.optJSONObject(id) ?: return false
            entry.put("sourceUrl", clean)
            writeLibraryIndex(index)
        } catch (_: Exception) { false }
    }

    fun deleteLibraryItem(id: String): Boolean {
        return try {
            val index = readLibraryIndex()
            val entry = index.optJSONObject(id) ?: return false
            val file = safeSrtFile(entry.optString("fileName"))
            removeEntryAndFile(index, id, entry, file, ::writeLibraryIndex)
        } catch (_: Exception) { false }
    }

    /**
     * يحفظ SRT مرتبطاً بـ URL.
     * @return اسم الملف المحفوظ، أو null عند الفشل.
     */
    fun save(url: String, srtText: String, cueCount: Int): String? {
        var createdFile: File? = null
        var tempFile: File? = null
        return try {
            val normalizedUrl = normalizeUrl(url)
            if (normalizedUrl.isBlank() || srtText.isBlank() || url.trim().startsWith("local-media://", true)) return null

            // Read the existing index before writing any new data. A damaged index must not
            // silently become an empty index and erase the user's previous cache entries.
            val index = readIndex()
            val existing = findEntryPair(index, url)
            val indexKey = existing?.first ?: hashUrl(url)
            val oldFileName = existing?.second?.optString("fileName")
            // Version each saved SRT so a failed index update never overwrites the last good copy.
            val fileName = "${hashUrl(url)}_${UUID.randomUUID()}.srt"
            val outputFile = safeSrtFile(fileName) ?: return null
            createdFile = outputFile
            tempFile = File(dir, "$fileName.tmp")
            tempFile!!.writeText(srtText, Charsets.UTF_8)
            if (!tempFile!!.isFile || tempFile!!.length() <= 0L) return null
            if (!tempFile!!.renameTo(outputFile)) {
                tempFile!!.copyTo(outputFile, overwrite = false)
                tempFile!!.delete()
            }
            if (!outputFile.isFile || outputFile.length() <= 0L) return null

            index.put(indexKey, JSONObject().apply {
                put("url", url.trim())
                put("normalizedUrl", normalizedUrl)
                put("canonicalKey", canonicalKey(url))
                put("fileName", fileName)
                put("cueCount", cueCount)
                put("savedAt", System.currentTimeMillis())
            })
            if (!writeIndex(index)) return null

            // Only remove the old copy after the new SRT and its index entry are durable.
            if (!oldFileName.isNullOrBlank() && oldFileName != fileName) safeSrtFile(oldFileName)?.delete()
            createdFile = null // committed; don't delete it in the cleanup path
            fileName
        } catch (_: Exception) {
            null
        } finally {
            tempFile?.delete()
            createdFile?.delete()
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
            val file = safeSrtFile(fileName) ?: return null
            if (!file.isFile) return null
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * هل يوجد SRT محفوظ لهذا URL؟
     */
    fun has(url: String): Boolean {
        return try {
            val entry = findEntry(readIndex(), url) ?: return false
            val fileName = entry.optString("fileName")
            safeSrtFile(fileName)?.isFile == true
        } catch (_: Exception) { false }
    }

    /**
     * يحذف SRT مرتبطاً بـ URL.
     */
    fun delete(url: String): Boolean {
        return try {
            val index = readIndex()
            val found = findEntryPair(index, url) ?: return false
            val (key, entry) = found
            val file = safeSrtFile(entry.optString("fileName"))
            removeEntryAndFile(index, key, entry, file, ::writeIndex)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes an index entry and its file as a recoverable transaction. The file is first
     * renamed within the same private directory; if the index commit fails, the original
     * filename is restored so the previous index remains valid.
     */
    private fun removeEntryAndFile(
        index: JSONObject,
        key: String,
        entry: JSONObject,
        file: File?,
        persist: (JSONObject) -> Boolean
    ): Boolean {
        val tombstone = if (file?.isFile == true) {
            File(dir, ".${file.name}.${UUID.randomUUID()}.deleting")
        } else null

        if (tombstone != null && !file!!.renameTo(tombstone)) return false

        index.remove(key)
        if (!persist(index)) {
            if (tombstone != null && tombstone.exists()) tombstone.renameTo(file!!)
            return false
        }

        // The library/index removal is committed. A rare filesystem refusal to remove the
        // tombstone leaves only an unindexed private file, never a visible stale library item.
        if (tombstone != null && tombstone.exists() && !tombstone.delete()) {
            val restoredFile = tombstone.renameTo(file!!)
            if (restoredFile) {
                index.put(key, entry)
                if (persist(index)) return false
                // If rollback persistence fails, keep the logical deletion committed rather
                // than report a successful restore that is not present on disk.
                index.remove(key)
            }
        }
        return true
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
    private fun normalizeUrl(raw: String): String = VideoUrlIdentity.normalize(raw)

    private fun findEntry(index: JSONObject, url: String): JSONObject? = findEntryPair(index, url)?.second

    private fun findEntryPair(index: JSONObject, url: String): Pair<String, JSONObject>? {
        val directHash = hashUrl(url)
        index.optJSONObject(directHash)?.let { return directHash to it }
        val normalized = normalizeUrl(url)
        val legacyNormalized = legacyNormalizeUrl(url)
        val canonical = canonicalKey(url)
        val keys = index.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = index.optJSONObject(key) ?: continue
            val storedCanonical = entry.optString("canonicalKey")
            if (canonical.isNotBlank() && storedCanonical == canonical) return key to entry
            val storedUrl = entry.optString("url")
            val storedNormalized = entry.optString("normalizedUrl").ifBlank { normalizeUrl(storedUrl) }
            if (storedNormalized == normalized || storedNormalized == legacyNormalized ||
                (storedUrl.isNotBlank() && legacyNormalizeUrl(storedUrl) == legacyNormalized)) return key to entry
        }
        return null
    }

    /** Previous Stage 22/25 URL normalization, retained only to read existing cache indexes. */
    private fun legacyNormalizeUrl(raw: String): String {
        return try {
            val cleaned = raw.trim().trimEnd('.', ',', ')', ']', '}', '،')
            val uri = Uri.parse(cleaned)
            var host = (uri.host ?: "").lowercase(java.util.Locale.ROOT)
            if (host.startsWith("www.")) host = host.removePrefix("www.")
            val path = uri.path.orEmpty().trimEnd('/')
            val status = Regex("(?:^|/)status/(\\d+)(?:/|$)").find(path)?.groupValues?.getOrNull(1)
            val isX = host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")
            if (isX && status != null) return "x-status:$status"
            val youtubeId = when {
                host == "youtu.be" -> path.removePrefix("/").substringBefore("/")
                (host == "youtube.com" || host.endsWith(".youtube.com")) && path == "/watch" -> uri.getQueryParameter("v").orEmpty()
                (host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
                else -> ""
            }
            if (youtubeId.isNotBlank()) return "youtube-id:$youtubeId"
            val allowed = uri.queryParameterNames.filterNot {
                it.equals("si", true) || it.equals("feature", true) || it.startsWith("utm_", true) ||
                    it.equals("fbclid", true) || it.equals("gclid", true)
            }.sorted().flatMap { key -> uri.getQueryParameters(key).map { key to it } }
            val query = allowed.joinToString("&") { (key, value) ->
                java.net.URLEncoder.encode(key, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
            }
            buildString {
                append(uri.scheme?.lowercase(java.util.Locale.ROOT) ?: "https").append("://").append(host)
                if (uri.port != -1) append(":").append(uri.port)
                append(path)
                if (query.isNotBlank()) append("?").append(query)
            }
        } catch (_: Exception) { raw.trim() }
    }

    /** Stable key for platforms with a durable video ID, such as X posts and YouTube videos. */
    private fun canonicalKey(raw: String): String = VideoUrlIdentity.canonicalKey(raw)

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
        // Preserve a malformed existing index rather than silently treating it as empty and
        // overwriting all prior library entries on the next save.
        return if (libraryIndexFile.exists()) {
            JSONObject(libraryIndexFile.readText(Charsets.UTF_8))
        } else JSONObject()
    }

    private fun writeLibraryIndex(index: JSONObject): Boolean = writeJsonIndex(libraryIndexFile, index)

    private fun writeJsonIndex(target: File, index: JSONObject): Boolean {
        val temp = File(dir, "${target.name}.tmp")
        return try {
            temp.writeText(index.toString(2), Charsets.UTF_8)
            if (!temp.isFile || temp.length() <= 0L) return false
            val staged = JSONObject(temp.readText(Charsets.UTF_8))
            if (!jsonEquivalent(staged, index)) return false
            // temp and target share a directory; rename is the commit step. If it fails,
            // leave the previous index untouched instead of copying over it non-atomically.
            if (!temp.renameTo(target)) return false
            val persisted = JSONObject(target.readText(Charsets.UTF_8))
            jsonEquivalent(persisted, index)
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    private fun jsonEquivalent(actual: JSONObject, expected: JSONObject): Boolean {
        if (actual.length() != expected.length()) return false
        val keys = expected.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val actualValue = actual.opt(key)
            val expectedValue = expected.opt(key)
            if (actualValue is JSONObject && expectedValue is JSONObject) {
                if (!jsonEquivalent(actualValue, expectedValue)) return false
            } else if (actualValue?.toString() != expectedValue?.toString()) {
                return false
            }
        }
        return true
    }

    private fun safeSrtFile(fileName: String): File? {
        if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\') ||
            !fileName.matches(Regex("[A-Za-z0-9_.-]+\\.srt"))) return null
        return try {
            val root = dir.canonicalFile
            val file = File(dir, fileName).canonicalFile
            if (file.parentFile == root) file else null
        } catch (_: Exception) { null }
    }

    private fun readIndex(): JSONObject {
        if (!indexFile.exists()) return JSONObject()
        return try {
            JSONObject(indexFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("فهرس SRT تالف؛ تم إيقاف الحفظ لحماية السجلات القديمة.", e)
        }
    }

    private fun writeIndex(index: JSONObject): Boolean = writeJsonIndex(indexFile, index)

}
