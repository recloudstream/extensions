package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.lang.RuntimeException

class TwitchProvider : MainAPI() {
    override var mainUrl = "https://twitchtracker.com" // Easiest to scrape
    override var name = "Twitch"
    override val supportedTypes = setOf(TvType.Live)

    override var lang = "uni"

    override val hasMainPage = true
    private val gamesName = "games"

    override val mainPage = mainPageOf(
        "$mainUrl/channels/live" to "Top global live streams",
        "$mainUrl/games" to gamesName
    )
    private val isHorizontal = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            gamesName -> newHomePageResponse(parseGames(), hasNext = false) // Get top games
            else -> {
                val doc = app.get(request.data, params = mapOf("page" to page.toString())).document
                val channels = doc.select("table#channels tr").map { element ->
                    element.toLiveSearchResponse()
                }
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name,
                            channels,
                            isHorizontalImages = isHorizontal
                        )
                    ),
                    hasNext = true
                )
            }
        }
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse {
        val anchor = this.select("a")
        val linkName = anchor.attr("href").substringAfterLast("/")
        val name = anchor.firstOrNull { it.text().isNotBlank() }?.text()
        val image = this.select("img").attr("src")
        return newLiveSearchResponse(
            name ?: "",
            linkName,
            TvType.Live,
            fix = false
        ) { posterUrl = image }
    }

    private suspend fun parseGames(): List<HomePageList> {
        val doc = app.get("$mainUrl/games").document
        return doc.select("div.ranked-item")
            .take(5)
            .mapNotNull { element -> // No apmap to prevent getting 503 by cloudflare
                val game = element.select("div.ri-name > a")
                val url = fixUrl(game.attr("href"))
                val name = game.text()
                val searchResponses = parseGame(url).ifEmpty { return@mapNotNull null }
                HomePageList(name, searchResponses, isHorizontalImages = isHorizontal)
            }
    }

    private suspend fun parseGame(url: String): List<LiveSearchResponse> {
        val doc = app.get(url).document
        return doc.select("td.cell-slot.sm").map { element ->
            element.toLiveSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val realUrl = url.substringAfterLast("/")
        val doc = app.get("$mainUrl/$realUrl", referer = mainUrl).document
        val name = doc.select("div#app-title").text()
        if (name.isBlank()) {
            throw RuntimeException("Could not load page, please try again.\n")
        }
        val rank = doc.select("div.rank-badge > span").last()?.text()?.toIntOrNull()
        val image = doc.select("div#app-logo > img").attr("src")
        val poster = doc.select("div.embed-responsive > img").attr("src").ifEmpty { image }
        val description = doc.select("div[style='word-wrap:break-word;font-size:12px;']").text()
        val language = doc.select("a.label.label-soft").text().ifEmpty { null }
        val isLive = doc.select("div.live-indicator-container").isNotEmpty()

        val tags = listOfNotNull(
            isLive.let { if (it) "Live" else "Offline" },
            language,
            rank?.let { "Rank: $it" },
        )

        val twitchUrl = "https://twitch.tv/$realUrl"

        return newLiveStreamLoadResponse(
            name, twitchUrl, twitchUrl
        ) {
            plot = description
            posterUrl = image
            backgroundPosterUrl = poster
            this@newLiveStreamLoadResponse.tags = tags
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document =
            app.get("$mainUrl/search", params = mapOf("q" to query), referer = mainUrl).document
        return document.select("table.tops tr").map { it.toLiveSearchResponse() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, subtitleCallback, callback)
    }

    class TwitchExtractor : ExtractorApi() {
        override val mainUrl = "https://twitch.tv/"
        override val name = "Twitch"
        override val requiresReferer = false

        data class ApiResponse(
            val success: Boolean,
            val urls: Map<String, String>?
        )

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val response =
                app.get("https://pwn.sh/tools/streamapi.py?url=$url").parsed<ApiResponse>()
            response.urls?.forEach { (name, url) ->
                val quality = getQualityFromName(name.substringBefore("p"))
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "${this.name} ${name.replace("${quality}p", "")}",
                        url
                    ) {
                        this.type = ExtractorLinkType.M3U8
                        this.quality = quality
                        this.referer = ""
                    }
                )
            }
        }
    }
}
