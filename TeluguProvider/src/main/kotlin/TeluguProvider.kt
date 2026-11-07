package com.shekar.telugu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class TeluguProvider : MainProvider() {
    override var mainUrl = "https://www.5movierulz.supplies"
    override var name = "Telugu Provider"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "te"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("div.movies-list div.item").mapNotNull { element ->
            val title = element.select("h2 a").text()
            val href = element.select("h2 a").attr("href")
            val posterUrl = element.select("img").attr("src")
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(arrayListOf(HomePageList("Latest Movies", home)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movies-list div.item").mapNotNull { element ->
            val title = element.select("h2 a").text()
            val href = element.select("h2 a").attr("href")
            val posterUrl = element.select("img").attr("src")
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1").text()
        val poster = document.select("div.post-thumbnail img").attr("src")
        val description = document.select("div.entry-content").text()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }
}
