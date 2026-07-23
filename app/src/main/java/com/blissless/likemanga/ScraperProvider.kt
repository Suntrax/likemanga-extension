package com.blissless.likemanga

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider queried by the Oni manga client.
 *
 * Two query paths:
 *
 * 1. List all chapters (metadata only, no images):
 *    content://com.blissless.likemanga.provider/chapters
 *      ?manga=<title>&anilistId=<id>
 *    Returns: { "totalChapters": N, "chapters": [...] }
 *
 * 2. Scrape a single chapter's image URLs:
 *    content://com.blissless.likemanga.provider/scrape
 *      ?manga=<title>&anilistId=<id>&chapter=<number>
 *    Returns: { "totalChapters": N, "chapter": {"number":"1","images":[...]} }
 *
 * Returns a single-row MatrixCursor whose "data" column holds the JSON string.
 */
class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.likemanga.provider"
        const val PATH_SCRAPE = "scrape"
        const val PATH_CHAPTERS = "chapters"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
        private const val CODE_CHAPTERS = 2
        private const val TAG = "LikeManga"
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
        addURI(AUTHORITY, PATH_CHAPTERS, CODE_CHAPTERS)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String?>?, selection: String?,
        selectionArgs: Array<out String?>?, sortOrder: String?
    ): Cursor? {
        when (uriMatcher.match(uri)) {
            CODE_SCRAPES -> {
                val mangaName = uri.getQueryParameter("manga")
                val anilistId = uri.getQueryParameter("anilistId")
                val chapter   = uri.getQueryParameter("chapter")
                val cursor = MatrixCursor(arrayOf("data"))
                Log.d(TAG, "scrape: manga='$mangaName' anilistId=$anilistId chapter='$chapter'")
                try {
                    val result = LikeMangaScraper.scrape(context!!, mangaName, anilistId, chapter)
                    val json = serializeResult(result)
                    Log.d(TAG, "scrape result (${json.length} chars): ${json.take(200)}")
                    cursor.addRow(arrayOf(json))
                } catch (e: Exception) {
                    Log.e(TAG, "scrape threw", e)
                    val msg = e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: "unknown error"
                    cursor.addRow(arrayOf("{\"error\":\"Scraping failed: $msg\"}"))
                }
                return cursor
            }
            CODE_CHAPTERS -> {
                val mangaName = uri.getQueryParameter("manga")
                val anilistId = uri.getQueryParameter("anilistId")
                val cursor = MatrixCursor(arrayOf("data"))
                Log.d(TAG, "chapters: manga='$mangaName' anilistId=$anilistId")
                try {
                    val result = LikeMangaScraper.listChapters(context!!, mangaName, anilistId)
                    val json = serializeResult(result)
                    Log.d(TAG, "chapters result (${json.length} chars): ${json.take(200)}")
                    cursor.addRow(arrayOf(json))
                } catch (e: Exception) {
                    Log.e(TAG, "chapters threw", e)
                    val msg = e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: "unknown error"
                    cursor.addRow(arrayOf("{\"error\":\"Listing chapters failed: $msg\"}"))
                }
                return cursor
            }
        }
        return null
    }

    private fun serializeResult(result: Any): String {
        return when (result) {
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((key, value) in result) {
                    when (value) {
                        is Map<*, *> -> obj.put(key.toString(), JSONObject(value as Map<*, *>))
                        is List<*> -> {
                            val arr = JSONArray()
                            for (item in value) {
                                when (item) {
                                    is Map<*, *> -> arr.put(JSONObject(item as Map<*, *>))
                                    else -> arr.put(item)
                                }
                            }
                            obj.put(key.toString(), arr)
                        }
                        is JSONArray -> obj.put(key.toString(), value)
                        is JSONObject -> obj.put(key.toString(), value)
                        null -> obj.put(key.toString(), JSONObject.NULL)
                        else -> obj.put(key.toString(), value)
                    }
                }
                obj.toString()
            }
            else -> result.toString()
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String?>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String?>?): Int = 0
}
