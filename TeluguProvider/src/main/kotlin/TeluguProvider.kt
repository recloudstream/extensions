package com.shekar.telugu

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TeluguProvider : MainAPI() {
    override var mainUrl = "https://www.5movierulz.supplies"
    override var name = "MovieRulz Telugu"
    override val hasMainPage = true
    override var lang = "te"
    override val supportedTypes = setOf(TvType.Movie)

    private val tag = "TeluguDbg"

    override val mainPage = mainPageOf(
        "$mainUrl/category/telugu-movies-2026" to "Telugu 2026",
        "$mainUrl/category/telugu-movies-2025" to "Telugu 2025",
        "$mainUrl/category/telugu-movies-2024" to "Telugu 2024",
        "$mainUrl/language/telugu-dubbed" to "Telugu Dubbed"
    )

    // ---------- listing ----------

    private fun Document.parseCards(): List<SearchResponse> =
        select("a[href$=.html]:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(attr("href"))
        val img = selectFirst("img")
        val title = attr("title").ifBlank { img?.attr("alt").orEmpty() }
            .ifBlank { text() }.trim()
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        )
        if (title.isBlank() || href.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url, referer = mainUrl).document
        val home = doc.parseCards()
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val patterns = listOf("$mainUrl/search_movies?s=$q", "$mainUrl/?s=$q")
        for (u in patterns) {
            val results = try {
                app.get(u, referer = mainUrl).document.parseCards()
            } catch (e: Exception) {
                emptyList()
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    // ---------- details ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document

        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h2")?.text() ?: "Movie")
            .substringBefore(" | MovieRulz").trim()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("h4:containsOwn(Synopsis)")?.nextElementSibling()?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------- link extraction ----------

    private val whitespace = Regex("\\s+")
    private val urlRegex = Regex("""https?://[^\s"'<>\\)]+""")
    private val skipExt = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
        ".css", ".js", ".ico", ".woff", ".woff2", ".ttf"
    )
    private val skipHost = listOf(
        "googleapis", "google-analytics", "googletagmanager", "gstatic",
        "facebook", "twitter", "w3.org", "schema.org",
        "cdnjs.cloudflare.com", "whatsapp", "//t.me", "telegram", "instagram",
        "cloudflareinsights", "acscdn.com"
    )
    private val embedHint =
        Regex("ajax|embed|player|server|stream|api|source|watch", RegexOption.IGNORE_CASE)

    private val mediaRegex =
        Regex("""https?://[^\s"'<>\\]+?\.(m3u8|mp4)[^\s"'<>\\]*""")

    private fun collectUrls(text: String, out: MutableSet<String>) {
        val clean = text.replace("\\/", "/").replace("&amp;", "&")
        urlRegex.findAll(clean).forEach { out.add(it.value.trimEnd(',', ';', '\\', '.')) }
    }

    private fun tryBase64(s: String): String? {
        if (s.length < 16 || !s.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return null
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            String(Base64.decode(fixed, Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    private fun isUseful(u: String): Boolean {
        if (u.startsWith("magnet")) return false
        val path = u.substringBefore('?').lowercase()
        if (skipExt.any { path.endsWith(it) }) return false
        if (skipHost.any { u.contains(it, true) }) return false
        return true
    }

    // Other addresses of the same video, for hosts Cloudstream may not know
    private fun alternates(link: String): List<String> {
        val id = link.trimEnd('/').substringAfterLast('/').substringBefore('?')
        return when {
            link.contains("hglink.to") -> listOf(
                "https://streamwish.to/e/$id",
                "https://hlswish.com/e/$id",
                "https://swiftplayers.com/e/$id"
            )
            link.contains("minochinos") -> listOf(
                "https://filelions.to/v/$id",
                "https://vidhide.com/v/$id",
                "https://dhcplay.com/v/$id"
            )
            else -> emptyList()
        }
    }

    // Used when Cloudstream has no built-in extractor for a video host
    private suspend fun fallbackExtract(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        try {
            val text = app.get(link, referer = referer).text.replace("\\/", "/")
            mediaRegex.findAll(text).map { it.value }.distinct().forEach { m ->
                val isM3u8 = m.contains(".m3u8")
                callback(
                    newExtractorLink(name, name, m) {
                        this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        this.referer = link
                        this.quality = Qualities.Unknown.value
                    }
                )
                ok = true
            }
        } catch (_: Exception) {
        }
        return ok
    }

    private suspend fun debugLink(info: String): ExtractorLink {
        return newExtractorLink(name, info, "https://example.com/debug.m3u8") {
            this.type = ExtractorLinkType.M3U8
            this.quality = Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Open the movie page, up to 3 tries (the site is sometimes slow or refuses)
        var res: NiceResponse? = null
        var err = ""
        for (i in 1..3) {
            try {
                val r = app.get(data, referer = mainUrl, timeout = 30L)
                res = r
                if (r.code == 200 && r.text.contains("stream-link-btn")) {
                    err = ""
                    break
                }
                err = "try$i status=${r.code} len=${r.text.length}"
            } catch (e: Exception) {
                err = "try$i " + e.javaClass.simpleName + ":" + (e.message ?: "").take(60)
            }
        }

        if (res == null) {
            callback(debugLink("DEBUG page failed $err"))
            return true
        }
        val page = res

        val doc = page.document
        val rawFound = linkedSetOf<String>()

        // 0) the site's own server buttons (their links contain a line break inside)
        doc.select("a.stream-link-btn").forEach { a ->
            val h = a.attr("href").replace(whitespace, "")
            if (h.startsWith("http")) rawFound.add(h)
        }

        // 1) every attribute of every element
        doc.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val v = attr.value.trim()
                when {
                    v.startsWith("http") -> rawFound.add(v)
                    v.startsWith("//") -> rawFound.add("https:$v")
                    v.contains("http") -> collectUrls(v, rawFound)
                    else -> tryBase64(v)?.let { d ->
                        if (d.contains("http")) collectUrls(d, rawFound)
                    }
                }
            }
        }

        // 2) raw HTML + inline scripts
        collectUrls(page.text, rawFound)
        doc.select("script").forEach { s ->
            collectUrls(s.data(), rawFound)
            Regex("""["']([A-Za-z0-9+/=_-]{24,})["']""").findAll(s.data()).forEach { m ->
                tryBase64(m.groupValues[1])?.let { d ->
                    if (d.contains("http")) collectUrls(d, rawFound)
                }
            }
        }

        // 3) remove line breaks / spaces inside links, then drop cut-off copies
        val cleaned = rawFound.map { it.replace(whitespace, "") }.toSet()
        val found = cleaned.filter { u ->
            cleaned.none { o -> o != u && o.startsWith(u) }
        }

        Log.d(tag, "page=${page.url} status=${page.code} found=${found.size}")
        found.forEach { Log.d(tag, "found: $it") }

        val useful = found.filter { isUseful(it) }
        val internal = useful.filter { it.startsWith(mainUrl) }
        val external = useful.filter { !it.startsWith(mainUrl) }.toMutableSet()

        // 4) follow same-site embed / ajax endpoints once
        internal
            .filter { embedHint.containsMatchIn(it) && !it.contains("/category/") && !it.endsWith(".html") }
            .take(8)
            .forEach { u ->
                try {
                    val r = app.get(
                        u,
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                    val sub = linkedSetOf<String>()
                    collectUrls(r.text, sub)
                    sub.map { it.replace(whitespace, "") }
                        .filter { isUseful(it) && !it.startsWith(mainUrl) }
                        .forEach { external.add(it) }
                } catch (_: Exception) {
                }
            }

        Log.d(tag, "useful=${useful.size} external=${external.size}")
        external.forEach { Log.d(tag, "ext: $it") }

        var ok = false
        external.take(50).forEach { link ->
            try {
                var hit = loadExtractor(link, data, subtitleCallback, callback)
                if (!hit) hit = fallbackExtract(link, data, callback)
                if (!hit) {
                    for (alt in alternates(link)) {
                        if (loadExtractor(alt, data, subtitleCallback, callback)) {
                            hit = true
                            break
                        }
                    }
                }
                if (hit) ok = true
            } catch (_: Exception) {
            }
        }
        Log.d(tag, "final ok=$ok")

        if (!ok) {
            val hosts = external.map { it.substringAfter("//").substringBefore("/") }
                .distinct().joinToString(",")
            val info = "DEBUG status=${page.code} len=${page.text.length} " +
                "btn=${page.text.contains("stream-link-btn")} " +
                "found=${found.size} ext=${external.size} hosts=$hosts $err"
            callback(debugLink(info))
            return true
        }
        return ok
    }
}
