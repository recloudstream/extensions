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

    // Defines the tabs on the home screen
    override val mainPage = mainPageOf(
        "$mainUrl/category/telugu-movies-2024/" to "Telugu 2024",
        "$mainUrl/category/telugu-movies-2023/" to "Telugu 2023",
        "$mainUrl/category/dubbed-movies/" to "Dubbed Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Fetches the HTML from the website
        val document = app.get(request.data).document
        
        // Scrapes the movie boxes
        val home = document.select("div.content div.box").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        
        return document.select("div.content div.box").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        throw NotImplementedError("We will build the movie details and streaming links next!")
    }
}
