package com.shekar.telugu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TeluguProvider : MainAPI() {
    override var mainUrl = "https://www.5movierulz.supplies"
    override var name = "MovieRulz Telugu"
    override val hasMainPage = true
    override var lang = "te"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/category/telugu-movies-2024/" to "Telugu 2024",
        "$mainUrl/category/telugu-movies-2023/" to "Telugu 2023",
        "$mainUrl/category/dubbed-movies/" to "Dubbed Movies"
    )

    private val itemSelector =
        "div.content div.item, div.cont_display, div.movies-list div.item, article, li.film"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url, referer = mainUrl).document
        val home = document.select(itemSelector).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img")
        val title = link.attr("title").ifBlank {
            img?.attr("alt").orEmpty().ifBlank { link.text() }
        }.trim()
        val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        if (title.isBlank() || href.isBlank()) return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$q", referer = mainUrl).document
        return document.select(itemSelector).mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1.entry-title, h1")?.text()
            ?: "Movie"
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.entry-content img, .post-thumbnail img")?.attr("src")
        )
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("div.entry-content p")?.text()

        // Pass the movie PAGE url as data so loadLinks() can sweep all candidate streams
        return newMovieLoadResponse(title.trim(), url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        val candidates = linkedSetOf<String>()

        // 1) iframes (including lazy-loaded ones)
        document.select("iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank()) candidates.add(fixUrl(src))
        }

        // 2) external anchors (watch-online buttons pointing to video hosts)
        document.select("a[href]").forEach {
            val href = it.attr("href")
            if (href.startsWith("http") &&
                !href.startsWith(mainUrl) &&
                !href.startsWith("magnet:") &&
                !href.endsWith(".torrent")
            ) candidates.add(href)
        }

        // 3) URLs hidden inside inline scripts
        val scriptRegex = Regex("""https?:\\?/\\?/[^\s"'<>\\]+""")
        document.select("script").forEach { s ->
            scriptRegex.findAll(s.data()).forEach { m ->
                candidates.add(m.value.replace("\\/", "/"))
            }
        }

        var found = false
        candidates.take(40).forEach { link ->
            if (loadExtractor(link, subtitleCallback, callback)) found = true
        }
        return found
    }
}
