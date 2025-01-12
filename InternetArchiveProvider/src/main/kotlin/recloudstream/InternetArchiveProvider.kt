package recloudstream

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.util.regex.Pattern
import kotlin.math.roundToInt

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper by lazy {
        // Some metadata uses different formats. We have to handle that here.
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val responseText = app.get("$mainUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=26&page=$page&output=json").text
            val featured = tryParseJson<SearchResult>(responseText)
            val homePageList = featured?.response?.docs?.map { it.toSearchResponse(this) } ?: emptyList()
            newHomePageResponse(
                listOf(
                    HomePageList("Featured", homePageList, true)
                ),
                false
            )
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseText = app.get("$mainUrl/advancedsearch.php?q=${query.encodeUri()}+mediatype:(movies OR audio)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=26&output=json").text
            val res = tryParseJson<SearchResult>(responseText)
            res?.response?.docs?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val identifier = url.substringAfterLast("/")
            val responseText = app.get("$mainUrl/metadata/$identifier").text
            val res = mapper.readValue<MetadataResult>(responseText)
            res.toLoadResponse(this)
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Error loading: Invalid JSON response")
        }
    }

    private data class SearchResult(
        val response: DocsResponse
    )

    private data class DocsResponse(
        val docs: List<SearchEntry>
    )

    private data class SearchEntry(
        val identifier: String,
        val mediatype: String,
        val title: String?
    ) {
        fun toSearchResponse(provider: InternetArchiveProvider): SearchResponse {
            val type = if (mediatype == "audio") {
                TvType.Audio
            } else TvType.Movie
            return provider.newMovieSearchResponse(
                title ?: identifier,
                "${provider.mainUrl}/details/$identifier",
                type
            ) {
                this.posterUrl = "${provider.mainUrl}/services/img/$identifier"
            }
        }
    }

    private data class MetadataResult(
        val metadata: MediaEntry,
        val files: List<MediaFile>,
        val dir: String,
        val server: String
    ) {
        companion object {
            private val seasonEpisodePatterns by lazy {
                listOf(
                    Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE), // S01E01
                    Regex("S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE), // S01 E01
                    Regex("Season\\s*(\\d+)\\D*Episode\\s*(\\d+)", RegexOption.IGNORE_CASE), // Season 1 Episode 1
                    Regex("Episode\\s*(\\d+)\\D*Season\\s*(\\d+)", RegexOption.IGNORE_CASE), // Episode 1 Season 1
                    Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE) // Episode 1
                )
            }
        }

        private fun extractEpisodeInfo(fileName: String): Pair<Int?, Int?> {
            for (pattern in seasonEpisodePatterns) {
                val matchResult = pattern.find(fileName)
                if (matchResult != null) {
                    val groups = matchResult.groupValues
                    return when (groups.count()) {
                        3 -> Pair(groups[1].toIntOrNull(), groups[2].toIntOrNull()) // S01E01, S01 E01
                        2 -> Pair(null, groups[1].toIntOrNull()) // Episode 1
                        5 -> Pair(groups[1].toIntOrNull(), groups[3].toIntOrNull()) // Season 1 Episode 1
                        else -> Pair(null, null)
                    }
                }
            }
            return Pair(null, null)
        }

        private fun extractYear(dateString: String?): Int? {
            // If it is impossible to find a date in the given string,
            // we can exit early
            if (dateString == null || dateString.length < 4) return null

            if (dateString.length == 4) {
                // If the date is already a year (or it can not be one),
                // we can exit early
                return dateString.toIntOrNull()
            }

            val yearPattern = "\\b(\\d{4})\\b"
            val yearRangePattern = "\\b(\\d{4})-(\\d{4})\\b"

            // Check for year ranges like YYYY-YYYY and get the start year if a match is found
            val yearRangeMatcher = Pattern.compile(yearRangePattern).matcher(dateString)
            if (yearRangeMatcher.find()) {
                return yearRangeMatcher.group(1)?.toInt()
            }

            // Check for single years within the date string in various formats
            val yearMatcher = Pattern.compile(yearPattern).matcher(dateString)
            if (yearMatcher.find()) {
                return yearMatcher.group(1)?.toInt()
            }

            return null
        }

        private fun extractQuality(height: Int?): Int {
            return when (height) {
                Qualities.P144.value -> Qualities.P144.value
                Qualities.P240.value -> Qualities.P240.value
                Qualities.P360.value -> Qualities.P360.value
                Qualities.P480.value -> Qualities.P480.value
                Qualities.P720.value -> Qualities.P720.value
                Qualities.P1080.value -> Qualities.P1080.value
                Qualities.P1440.value -> Qualities.P1440.value
                Qualities.P2160.value -> Qualities.P2160.value
                else -> Qualities.Unknown.value
            }
        }

        private fun getThumbnailUrl(fileName: String): String? {
            val thumbnail = files.find {
                it.format == "Thumbnail" && it.original == fileName
            }
            return thumbnail?.let { "https://$server$dir/${it.name}" }
        }

        private fun getCleanedName(fileName: String): String {
            return fileName
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace('_', ' ')
        }

        private fun getUniqueName(fileName: String): String {
            return getCleanedName(fileName)
                // Some files have versions with very similar names.
                // In this case, we do not want treat the files as
                // separate when checking for uniqueness, otherwise it
                // will think it is a playlist when that is not the case.
                .substringBeforeLast(".")
                .replace("512kb", "")
                .trim()
        }

        private fun cleanHtml(html: String): String {
            // We need to make sure descriptions use the correct text
            // color/style of the rest of the app for consistency.
            val document: Document = Jsoup.parse(html)
            val fontTags: Elements = document.select("font")
            for (fontTag: Element in fontTags) {
                fontTag.unwrap()
            }

            // Strip the last <div> tag to prevent to much padding
            // towards the end.
            val divTags: Elements = document.select("div")
            if (divTags.isNotEmpty()) {
                divTags.last()?.unwrap()
            }

            return document.body().html()
        }

        suspend fun toLoadResponse(provider: InternetArchiveProvider): LoadResponse {
            val videoFiles = files.asSequence()
                .filter {
                    it.lengthInSeconds >= 10.0 &&
                            (it.format.contains("MPEG", true) ||
                                    it.format.startsWith("H.264", true) ||
                                    it.format.startsWith("Matroska", true) ||
                                    it.format.startsWith("DivX", true) ||
                                    it.format.startsWith("Ogg Video", true))
                }

            val type = if (metadata.mediatype == "audio") {
                TvType.Audio
            } else TvType.Movie

            return if (videoFiles.distinctBy { getUniqueName(it.name) }.count() <= 1 || type == TvType.Audio) {
                // TODO if audio-playlist, use tracks
                provider.newMovieLoadResponse(
                    metadata.title ?: metadata.identifier,
                    "${provider.mainUrl}/details/${metadata.identifier}",
                    type,
                    metadata.identifier
                ) {
                    plot = metadata.description?.let { cleanHtml(it) }
                    year = extractYear(metadata.date)
                    tags = if (metadata.subject?.count() == 1) {
                        metadata.subject[0].split(";")
                    } else metadata.subject
                    posterUrl = "${provider.mainUrl}/services/img/${metadata.identifier}"
                    duration = ((videoFiles.firstOrNull()?.lengthInSeconds ?: 0f) / 60).roundToInt()
                    actors = metadata.creator?.map {
                        ActorData(Actor(it, ""), roleString = "Creator")
                    }
                }
            } else {
                /**
                 * This may not be a TV series but we use it for video playlists as
                 * it is better for resuming (or downloading) what specific track
                 * you are on.
                 */
                val urlMap = mutableMapOf<String, MutableSet<URLData>>()

                videoFiles.forEach { file ->
                    val cleanedName = getCleanedName(file.original ?: file.name)
                    val videoFileUrl = "https://$server$dir/${file.name}"
                    val fileQuality = extractQuality(file.height)
                    if (urlMap.containsKey(cleanedName)) {
                        urlMap[cleanedName]?.add(
                            URLData(
                                url = videoFileUrl,
                                format = file.format,
                                size = file.size ?: 0f,
                                quality = fileQuality
                            )
                        )
                    } else urlMap[cleanedName] = mutableSetOf(
                        URLData(
                            url = videoFileUrl,
                            format = file.format,
                            size = file.size ?: 0f,
                            quality = fileQuality
                        )
                    )
                }

                val mostFrequentLengthInMinutes = videoFiles
                    .map { (it.lengthInSeconds / 60).roundToInt() }
                    .groupBy { it }
                    .maxByOrNull { it.value.count() }
                    ?.key

                val episodes = urlMap.map { (fileName, urlData) ->
                    val file = videoFiles.first { getCleanedName(it.name) == fileName }
                    val episodeInfo = extractEpisodeInfo(file.original ?: file.name)

                    provider.newEpisode(
                        LoadData(
                            urlData = urlData,
                            type = "video-playlist"
                        ).toJson()
                    ) {
                        name = file.title ?: fileName
                        season = episodeInfo.first
                        episode = episodeInfo.second
                        runTime = (file.lengthInSeconds / 60).roundToInt()
                        posterUrl = getThumbnailUrl(file.original ?: file.name)
                    }
                }.sortedWith(compareBy({ it.season }, { it.episode }))

                provider.newTvSeriesLoadResponse(
                    metadata.title ?: metadata.identifier,
                    "${provider.mainUrl}/details/${metadata.identifier}",
                    TvType.TvSeries,
                    episodes
                ) {
                    plot = metadata.description?.let { cleanHtml(it) }
                    year = extractYear(metadata.date)
                    tags = if (metadata.subject?.count() == 1) {
                        metadata.subject[0].split(";")
                    } else metadata.subject
                    posterUrl = "${provider.mainUrl}/services/img/${metadata.identifier}"
                    duration = mostFrequentLengthInMinutes
                    actors = metadata.creator?.map {
                        ActorData(Actor(it, ""), roleString = "Creator")
                    }
                }
            }
        }
    }

    private data class MediaEntry(
        val identifier: String,
        val mediatype: String,
        val title: String?,
        val description: String?,
        val subject: List<String>?,
        val creator: List<String>?,
        val date: String?
    )

    private data class MediaFile(
        val name: String,
        val format: String,
        val title: String?,
        val original: String?,
        val length: String?,
        val size: Float?,
        val height: Int?
    ) {
        val lengthInSeconds: Float by lazy { calculateLengthInSeconds() }

        private fun calculateLengthInSeconds(): Float {
            return length?.toFloatOrNull() ?: run {
                // Check if length is in a different format and convert to seconds
                if (length?.contains(":") == true) {
                    lengthToSeconds(length)
                } else 0f
            }
        }

        private fun lengthToSeconds(time: String): Float {
            val parts = time.split(":")
            return when (parts.count()) {
                2 -> {
                    val minutes = parts[0].toFloatOrNull() ?: 0f
                    val seconds = parts[1].toFloatOrNull() ?: 0f
                    (minutes * 60) + seconds
                }
                3 -> {
                    val hours = parts[0].toFloatOrNull() ?: 0f
                    val minutes = parts[1].toFloatOrNull() ?: 0f
                    val seconds = parts[2].toFloatOrNull() ?: 0f
                    (hours * 3600) + (minutes * 60) + seconds
                }
                else -> 0f
            }
        }
    }

    data class LoadData(
        val urlData: Set<URLData>,
        val type: String
    )

    data class URLData(
        val url: String,
        val format: String,
        val size: Float,
        val quality: Int
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = tryParseJson<LoadData>(data)
        // TODO if audio-playlist, use tracks
        if (load?.type == "video-playlist") {
            val distinctURLData = load.urlData.filterNot {
                it.format.endsWith("IA")
            }

            fun getName(format: String): String {
                return if (distinctURLData.count() > 1) {
                    "$name ($format)"
                } else name
            }

            distinctURLData.sortedByDescending { it.size }.forEach { urlData: URLData ->
                callback(
                    ExtractorLink(
                        this.name,
                        getName(urlData.format),
                        urlData.url,
                        "",
                        urlData.quality
                    )
                )
            }
        } else {
            loadExtractor(
                "https://archive.org/details/$data",
                subtitleCallback,
                callback
            )
        }
        return true
    }
}