package recloudstream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class InvidiousProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://iv.ggtyler.dev"
    override var name = "Invidious" // name of provider
    override val supportedTypes = setOf(TvType.Others)

    override var lang = "en"

    // enable this when your provider has a main page
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val popular = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/api/v1/popular?fields=videoId,title").text
        )
        val trending = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/api/v1/trending?fields=videoId,title").text
        )
        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Popular",
                    popular?.map { it.toSearchResponse(this) } ?: emptyList(),
                    true
                ),
                HomePageList(
                    "Trending",
                    trending?.map { it.toSearchResponse(this) } ?: emptyList(),
                    true
                )
            ),
            false
        )
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val res = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/api/v1/search?q=${query.encodeUri()}&page=1&type=video&fields=videoId,title").text
        )
        return res?.map { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)").find(url)?.groups?.get(1)?.value
        val res = tryParseJson<VideoEntry>(
            app.get("$mainUrl/api/v1/videos/$videoId?fields=videoId,title,description,recommendedVideos,author,authorThumbnails,formatStreams").text
        )
        return res?.toLoadResponse(this)
    }

    private data class SearchEntry(
        val title: String,
        val videoId: String
    ) {
        fun toSearchResponse(provider: InvidiousProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/watch?v=$videoId",
                TvType.Movie
            ) {
                this.posterUrl = "${provider.mainUrl}/vi/$videoId/mqdefault.jpg"
            }
        }
    }

    private data class VideoEntry(
        val title: String,
        val description: String,
        val videoId: String,
        val recommendedVideos: List<SearchEntry>,
        val author: String,
        val authorThumbnails: List<Thumbnail>
    ) {
        suspend fun toLoadResponse(provider: InvidiousProvider): LoadResponse {
            return provider.newMovieLoadResponse(
                title,
                "${provider.mainUrl}/watch?v=$videoId",
                TvType.Movie,
                videoId
            ) {
                plot = description
                posterUrl = "${provider.mainUrl}/vi/$videoId/hqdefault.jpg"
                recommendations = recommendedVideos.map { it.toSearchResponse(provider) }
                actors = listOf(
                    ActorData(
                        Actor(author, authorThumbnails.getOrNull(authorThumbnails.size - 1)?.url ?: ""),
                        roleString = "Author"
                    )
                )
            }
        }
    }

    private data class Thumbnail(
        val url: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(
            "https://youtube.com/watch?v=$data",
            subtitleCallback,
            callback
        )
        callback(
            newExtractorLink(this.name, this.name, "$mainUrl/api/manifest/dash/id/$data") {
                quality = Qualities.Unknown.value
                type = ExtractorLinkType.DASH
                referer = ""
            }
        )
        return true
    }
}
