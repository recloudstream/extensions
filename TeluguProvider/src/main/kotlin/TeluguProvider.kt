package com.shekar.telugu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val elements = document.select("div.content div.item, div.cont_display, div.movies-list div.item")
        val home = elements.mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = link.attr("title").ifEmpty { this.selectFirst("a img")?.attr("alt") ?: link.text() }
        val href = fixUrl(link.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        if (title.isEmpty() || href.isEmpty()) return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        val elements = document.select("div.content div.item, div.cont_display, div.movies-list div.item")
        
        return elements.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2")?.text() ?: "Movie"
        val poster = fixUrlNull(document.selectFirst("div.post-thumbnail img, .entry-content img")?.attr("src"))
        val description = document.selectFirst("div.entry-content p")?.text()
        val playerUrl = document.selectFirst("iframe")?.attr("src") ?: url

        return newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, subtitleCallback, callback)
        return true
    }
}
