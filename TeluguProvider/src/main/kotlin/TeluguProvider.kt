package com.shekar.telugu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TeluguProvider : MainAPI() {
    // ⚠️ UPDATE THIS DOMAIN if it stops working! Examples: .red, .social, .recipes
    override var mainUrl = "https://www.5movierulz.red" 
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
        
        // Updated HTML selector: MovieRulz uses "div.cont_display"
        val home = document.select("div.cont_display").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = link.attr("title").ifEmpty { link.text() }
        val href = fixUrl(link.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        if (title.isEmpty() || href.isEmpty()) return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        
        return document.select("div.cont_display").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        throw NotImplementedError("We will build the movie details next!")
    }
}
