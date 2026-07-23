package com.blissless.likemanga

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * LikeManga (likemanga.ink) scraper for the Oni manga client.
 *
 * This is a custom PHP manga CMS (NOT WordPress despite Madara-style CSS classes).
 *
 * API endpoints:
 *   1. Search: GET /?act=ajax&code=search_manga&keyword=<query>
 *      Returns HTML <li> items: <li><a href="/<slug>-<id>/"><img ...><h3>TITLE</h3>...</a></li>
 *   2. Chapter list: GET /<slug>-<id>/
 *      HTML page with <ul id="list_chapter_id_detail"><li class="wp-manga-chapter"><a href="...">Chapter N</a></li>...</ul>
 *      Paginated via AJAX: GET /?act=ajax&code=load_list_chapter&manga_id=<id>&page_num=<N>&chap_id=0&keyword=
 *      Returns JSON: { "list_chap": "<li>...</li>...", "nav": "..." }
 *   3. Chapter images: GET /<manga-slug>-<id>/chapter-<num>-<chapterId>/
 *      HTML with <div class="reading-detail"><img data-index="N" src="https://like.mgread.io/...">...</div>
 *      Image URLs are absolute, on like.mgread.io CDN. No Referer needed.
 *
 * Chapter URL pattern: /<manga-slug>-<manga-id>/chapter-<chapterNum>-<chapterId>/
 * The chapterId is the internal DB id needed for the image CDN path.
 */
object LikeMangaScraper {

    private const val TAG = "LikeManga"
    private const val BASE = "https://likemanga.ink"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private data class MangaHit(val slug: String, val id: String, val title: String)
    private data class ChapterHit(val url: String, val number: String, val title: String)

    // ---------- Public API ----------

    fun listChapters(
        context: Context,
        mangaName: String?,
        anilistId: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }

        // 1. Search for the manga
        val manga = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        } ?: return mapOf("error" to "No manga found for '$mangaName'.")

        // 2. Fetch the manga page to get page-1 chapters + manga_id
        val mangaPageHtml = try {
            httpGet("$BASE/${manga.slug}/")
        } catch (e: Exception) {
            return mapOf("error" to "Failed to load manga page: ${e.message}")
        }

        val mangaId = extractMangaId(mangaPageHtml) ?: manga.id
        val page1Chapters = parseChapterList(mangaPageHtml)
        val totalPages = extractTotalChapterPages(mangaPageHtml)

        // 3. Fetch remaining pages via AJAX
        val allChapters = page1Chapters.toMutableList()
        for (page in 2..totalPages) {
            try {
                val ajaxUrl = "$BASE/?act=ajax&code=load_list_chapter&manga_id=$mangaId&page_num=$page&chap_id=0&keyword="
                val response = httpGet(ajaxUrl)
                val json = JSONObject(response)
                val listChap = json.optString("list_chap", "")
                allChapters.addAll(parseChapterListHtml(listChap))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch chapter page $page: ${e.message}")
            }
        }

        // Chapters are newest-first on the page; reverse to oldest-first for reading order
        allChapters.reverse()

        // 4. Build the response
        val chapterList = mutableListOf<Map<String, Any?>>()
        for ((index, ch) in allChapters.withIndex()) {
            chapterList.add(mapOf(
                "number" to ch.number,
                "title" to ch.title,
                "id" to ch.url,
                "index" to index,
                "pageCount" to 0
            ))
        }

        return mapOf(
            "totalChapters" to allChapters.size,
            "mangaId" to mangaId,
            "chapters" to chapterList
        )
    }

    fun scrape(
        context: Context,
        mangaName: String?,
        anilistId: String?,
        chapter: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }
        if (chapter.isNullOrBlank()) {
            return mapOf("error" to "No chapter provided.")
        }

        // 1. Search for the manga
        val manga = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        } ?: return mapOf("error" to "No manga found for '$mangaName'.")

        // 2. Get chapter list to find the requested chapter
        val mangaPageHtml = try {
            httpGet("$BASE/${manga.slug}/")
        } catch (e: Exception) {
            return mapOf("error" to "Failed to load manga page: ${e.message}")
        }

        val mangaId = extractMangaId(mangaPageHtml) ?: manga.id
        val page1Chapters = parseChapterList(mangaPageHtml)
        val totalPages = extractTotalChapterPages(mangaPageHtml)

        val allChapters = page1Chapters.toMutableList()
        for (page in 2..totalPages) {
            try {
                val ajaxUrl = "$BASE/?act=ajax&code=load_list_chapter&manga_id=$mangaId&page_num=$page&chap_id=0&keyword="
                val response = httpGet(ajaxUrl)
                val json = JSONObject(response)
                val listChap = json.optString("list_chap", "")
                allChapters.addAll(parseChapterListHtml(listChap))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch chapter page $page: ${e.message}")
            }
        }

        val totalChapters = allChapters.size

        // 3. Find the requested chapter
        val match = findChapter(allChapters, chapter.trim())
        if (match == null) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter '$chapter' not found. Available range: 1–$totalChapters."
            )
        }

        // 4. Fetch the chapter page and extract images
        val chapterUrl = if (match.url.startsWith("http")) match.url else "$BASE${match.url}"
        val chapterHtml = try {
            httpGet(chapterUrl)
        } catch (e: Exception) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Failed to load chapter page: ${e.message}"
            )
        }

        val images = extractImages(chapterHtml)
        if (images.isEmpty()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter $chapter returned no images."
            )
        }

        val chapterObj = JSONObject()
        chapterObj.put("number", chapter.trim())
        chapterObj.put("title", match.title)
        chapterObj.put("group", "")
        chapterObj.put("images", org.json.JSONArray(images))

        return mapOf(
            "totalChapters" to totalChapters,
            "chapter" to chapterObj
        )
    }

    // ---------- Parsing helpers ----------

    /** Search for a manga via the AJAX suggest endpoint. Returns the best match. */
    private fun searchManga(query: String): MangaHit? {
        val url = "$BASE/?act=ajax&code=search_manga&keyword=${URLEncoder.encode(query, "UTF-8")}"
        val html = httpGet(url)

        // Parse: <li><a href="/<slug>-<id>/"><img src="..." alt="..."><h3>TITLE</h3>...</a></li>
        val itemRegex = Pattern.compile(
            """<li><a\s+href="(/([^"]+))/">\s*<img[^>]*>\s*<h3>([^<]*)</h3>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val matcher = itemRegex.matcher(html)
        val results = mutableListOf<MangaHit>()
        while (matcher.find()) {
            val fullSlug = matcher.group(2) ?: continue   // e.g. "one-piece-7701"
            val title = decodeEntities(matcher.group(3) ?: "").trim()
            if (title.isBlank()) continue
            // The manga ID is the trailing number in the slug
            val id = fullSlug.substringAfterLast('-')
            results.add(MangaHit(fullSlug, id, title))
        }

        if (results.isEmpty()) return null

        // Pick the best match: exact title, else first result
        val queryLower = query.trim().lowercase()
        for (r in results) {
            if (r.title.lowercase() == queryLower) return r
        }
        return results.first()
    }

    /** Extract the manga_id from the manga page HTML. */
    private fun extractMangaId(html: String): String? {
        // Look for data-manga="<id>" or <input type="hidden" name="manga_id" id="manga_id" value="<id>">
        val patterns = listOf(
            Pattern.compile("""data-manga="(\d+)""""),
            Pattern.compile("""id="manga_id"\s+value="(\d+)""""),
            Pattern.compile("""name="manga_id"\s+value="(\d+)""""),
            Pattern.compile("""id="manga_id"[^>]*value="(\d+)"""")
        )
        for (p in patterns) {
            val m = p.matcher(html)
            if (m.find()) return m.group(1)
        }
        return null
    }

    /** Parse chapter <li> items from the manga page's chapter list. */
    private fun parseChapterList(html: String): List<ChapterHit> {
        return parseChapterListHtml(html)
    }

    /** Parse chapter <li> items from a chunk of HTML (either full page or AJAX response). */
    private fun parseChapterListHtml(html: String): List<ChapterHit> {
        val out = mutableListOf<ChapterHit>()
        // <li class="wp-manga-chapter"><a href="/<slug>/chapter-<num>-<id>/">Chapter <num></a>...</li>
        val regex = Pattern.compile(
            """<li[^>]*class="[^"]*wp-manga-chapter[^"]*"[^>]*>\s*<a\s+href="([^"]+)"[^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val matcher = regex.matcher(html)
        while (matcher.find()) {
            val url = decodeEntities(matcher.group(1) ?: "")
            val text = decodeEntities(matcher.group(2) ?: "").trim()
            if (url.isBlank()) continue
            // Extract chapter number from the text (e.g. "Chapter 1188" → "1188")
            val number = extractChapterNumber(text)
            out.add(ChapterHit(url, number, text))
        }
        return out
    }

    /** Extract the total number of chapter-list pages from the manga page HTML. */
    private fun extractTotalChapterPages(html: String): Int {
        // Look for load_list_chapter(N) calls in the pagination nav
        val regex = Pattern.compile("""load_list_chapter\((\d+)\)""")
        val matcher = regex.matcher(html)
        var maxPage = 1
        while (matcher.find()) {
            val page = matcher.group(1)?.toIntOrNull() ?: 1
            if (page > maxPage) maxPage = page
        }
        return maxPage
    }

    /** Extract image URLs from a chapter page's HTML. */
    private fun extractImages(html: String): List<String> {
        val out = mutableListOf<String>()
        // Images are in <div class="reading-detail"> with <img data-index="N" src="https://like.mgread.io/...">
        val regex = Pattern.compile(
            """<img[^>]*src="(https://like\.mgread\.io/[^"]+)"""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = regex.matcher(html)
        while (matcher.find()) {
            out.add(matcher.group(1) ?: continue)
        }
        return out
    }

    private fun extractChapterNumber(text: String): String {
        // "Chapter 1188" → "1188", "Chapter 1.5" → "1.5"
        val regex = Pattern.compile("""(\d+(?:\.\d+)?)""")
        val matcher = regex.matcher(text)
        return if (matcher.find()) matcher.group(1) ?: text else text
    }

    private fun findChapter(chapters: List<ChapterHit>, requested: String): ChapterHit? {
        val requestedNorm = requested.trim()

        // Pass 1: exact match on number
        for (ch in chapters) {
            if (ch.number == requestedNorm) return ch
        }

        // Pass 2: numeric equality
        val requestedNum = requestedNorm.toDoubleOrNull()
        if (requestedNum != null) {
            for (ch in chapters) {
                val num = ch.number.toDoubleOrNull()
                if (num != null && num == requestedNum) return ch
            }
        }

        // Pass 3: the chapter number is contained in the text (e.g. "Chapter 1" contains "1")
        for (ch in chapters) {
            if (ch.title.contains("Chapter $requestedNorm", ignoreCase = true)) return ch
        }

        return null
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    // ---------- HTTP ----------

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
            setRequestProperty("Referer", "$BASE/")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": ${err.take(200)}" else ""}")
        } finally {
            conn.disconnect()
        }
    }
}
