package com.shekar.telugu

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TeluguProvider : MainAPI() {
    override var mainUrl = "https://www.5movierulz.supplies"
    override var name = "MovieRulz Telugu"
    override val hasMainPage = true
    override var lang = "te"
    override val supportedTypes = setOf(TvType.Movie)

    private val cfKiller = CloudflareKiller()
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
        val doc = app.get(url, referer = mainUrl, interceptor = cfKiller).document
        val home = doc.parseCards()
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val patterns = listOf("$mainUrl/?s=$q", "$mainUrl/search?q=$q", "$mainUrl/search/$q")
        for (u in patterns) {
            val results = try {
                app.get(u, referer = mainUrl, interceptor = cfKiller).document.parseCards()
            } catch (e: Exception) {
                emptyList()
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    // ---------- details ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl, interceptor = cfKiller).document

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

    private val urlRegex = Regex("""https?://[^\s"'<>\\)]+""")
    private val skipExt = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
        ".css", ".js", ".ico", ".woff", ".woff2", ".ttf"
    )
    private val skipHost = listOf(
        "googleapis", "google-analytics", "googletagmanager", "gstatic",
        "facebook", "twitter", "w3.org", "schema.org",
        "cdnjs.cloudflare.com", "whatsapp", "t.me", "telegram", "instagram"
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, referer = mainUrl, interceptor = cfKiller)
        val doc = res.document
        val found = linkedSetOf<String>()

        // 1) every attribute of every element (data-src, data-url, data-embed, onclick, ...)
        doc.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val v = attr.value.trim()
                when {
                    v.startsWith("http") -> found.add(v)
                    v.startsWith("//") -> found.add("https:$v")
                    v.contains("http") -> collectUrls(v, found)
                    else -> tryBase64(v)?.let { d ->
                        if (d.contains("http")) collectUrls(d, found)
                    }
                }
            }
        }

        // 2) raw HTML + inline scripts (also catches escaped / base64 urls)
        collectUrls(res.text, found)
        doc.select("script").forEach { s ->
            collectUrls(s.data(), found)
            Regex("""["']([A-Za-z0-9+/=_-]{24,})["']""").findAll(s.data()).forEach { m ->
                tryBase64(m.groupValues[1])?.let { d ->
                    if (d.contains("http")) collectUrls(d, found)
                }
            }
        }

        Log.d(tag, "page=${res.url} status=${res.code} found=${found.size}")
        found.forEach { Log.d(tag, "found: $it") }

        val useful = found.filter { isUseful(it) }
        val internal = useful.filter { it.startsWith(mainUrl) }
        val external = useful.filter { !it.startsWith(mainUrl) }.toMutableSet()

        // 3) follow same-site embed / ajax endpoints once and harvest their urls
        internal
            .filter { embedHint.containsMatchIn(it) && !it.contains("/category/") && !it.endsWith(".html") }
            .take(8)
            .forEach { u ->
                try {
                    val r = app.get(
                        u,
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        interceptor = cfKiller
                    )
                    val sub = linkedSetOf<String>()
                    collectUrls(r.text, sub)
                    sub.filter { isUseful(it) && !it.startsWith(mainUrl) }
                        .forEach { external.add(it) }
                } catch (_: Exception) {
                }
            }

        Log.d(tag, "useful=${useful.size} external=${external.size}")
        external.forEach { Log.d(tag, "ext: $it") }

        var ok = false
        external.take(50).forEach { link ->
            try {
                val hit = loadExtractor(link, data, subtitleCallback, callback)
                if (hit) {
                    ok = true
                } else if (fallbackExtract(link, data, callback)) {
                    ok = true
                }
            } catch (_: Exception) {
            }
        }
        Log.d(tag, "final ok=$ok")
        return ok
    }
}
