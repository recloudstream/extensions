package com.idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3U8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.json.JSONObject

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    private val altUrls = listOf(
        "https://z2.idlixku.com",
        "https://z1.idlixku.com"
    )
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun baseHeaders(referer: String? = null): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to mainUrl,
            "Referer" to (referer ?: "$mainUrl/"),
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }

    override val mainPage = mainPageOf(
        "movie-trending" to "Film Trending",
        "movie-latest" to "Film Terbaru",
        "series-trending" to "Series Trending",
        "series-latest" to "Series Terbaru",
    )

    // ---------- JSON helpers ----------

    private fun posterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return "https://image.tmdb.org/t/p/w500$path"
    }

    private fun parseYear(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank()) return null
        return Regex("""\d{4}""").find(dateStr)?.value?.toIntOrNull()
    }

    private fun JSONObject.optStr(key: String): String? {
        return if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    }

    private fun mapItemToSearch(obj: JSONObject): SearchResponse? {
        val title = obj.optStr("title") ?: return null
        val slug = obj.optStr("slug") ?: return null
        val contentType = obj.optStr("contentType") ?: obj.optStr("type") ?: "movie"
        val isSeries = contentType.contains("series", true)
        val poster = posterUrl(obj.optStr("posterPath"))
        val year = parseYear(obj.optStr("releaseDate") ?: obj.optStr("firstAirDate"))
        val qualityStr = obj.optStr("quality")
        val rating = obj.optDouble("voteAverage", Double.NaN).takeIf { !it.isNaN() }
        val href = if (isSeries) "$mainUrl/series/$slug" else "$mainUrl/movie/$slug"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                if (!qualityStr.isNullOrBlank()) addQuality(qualityStr)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                if (!qualityStr.isNullOrBlank()) addQuality(qualityStr)
            }
        }
    }

    private suspend fun apiGetJson(path: String, referer: String = "$mainUrl/"): JSONObject? {
        // coba mainUrl + fallback domain (atasi Cloudflare / domain mati)
        val urls = listOf(mainUrl) + altUrls.filter { it != mainUrl }
        var lastErr: Exception? = null
        for (base in urls.distinct()) {
            try {
                val res = app.get(
                    "$base$path",
                    headers = baseHeaders(referer),
                    timeout = 20
                )
                if (!res.isSuccessful) continue
                val txt = res.text.trim()
                if (txt.isEmpty() || txt.startsWith("<")) continue
                return JSONObject(txt)
            } catch (e: Exception) {
                lastErr = e
                continue
            }
        }
        // lempar error terakhir biar kelihatan di log kalau semua gagal
        if (lastErr != null) throw lastErr
        return null
    }

    private suspend fun apiPostJson(
        path: String,
        jsonBody: Map<String, Any?>,
        referer: String
    ): JSONObject? {
        return try {
            val res = app.post(
                if (path.startsWith("http")) path else "$mainUrl$path",
                headers = baseHeaders(referer) + mapOf("Content-Type" to "application/json"),
                json = jsonBody,
                timeout = 20
            )
            if (!res.isSuccessful) return null
            val txt = res.text.trim()
            if (txt.isEmpty() || txt.startsWith("<")) return null
            JSONObject(txt)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- SEARCH ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val json = apiGetJson("/api/search?q=${query.trim()}") ?: return emptyList()
        // API: { results: [...] }
        val arr = json.optJSONArray("results")
            ?: json.optJSONArray("data")
            ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            try {
                mapItemToSearch(arr.getJSONObject(i))?.let { out.add(it) }
            } catch (_: Exception) {}
        }
        return out
    }

    // ---------- MAIN PAGE ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val limit = 24
        val path = when (request.data) {
            "movie-trending" -> "/api/movies?page=$page&limit=$limit&sort=popularityScore"
            "movie-latest" -> "/api/movies?page=$page&limit=$limit&sort=createdAt"
            "series-trending" -> "/api/series?page=$page&limit=$limit&sort=popularityScore"
            "series-latest" -> "/api/series?page=$page&limit=$limit&sort=createdAt"
            else -> "/api/movies?page=$page&limit=$limit&sort=createdAt"
        }
        val json = apiGetJson(path) ?: return newHomePageResponse(request.name, emptyList())
        val arr = json.optJSONArray("data") ?: return newHomePageResponse(request.name, emptyList())
        val list = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            try {
                mapItemToSearch(arr.getJSONObject(i))?.let { list.add(it) }
            } catch (_: Exception) {}
        }
        return newHomePageResponse(request.name, list)
    }

    // ---------- DETAIL ----------

    private fun slugFromUrl(url: String): String {
        // https://z2.idlixku.com/movie/per-aspera-ad-astra-2026?play=1 -> per-aspera-ad-astra-2026
        return url.substringAfterLast("/").substringBefore("?").trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series")
        val slug = slugFromUrl(url)

        if (!isSeries) {
            // ---- MOVIE ----
            val detail = apiGetJson("/api/movies/$slug", referer = url)
                ?: throw ErrorLoadingException("Gagal load detail. Coba lagi / ganti domain.")
            val title = detail.optStr("title") ?: slug
            val poster = posterUrl(detail.optStr("posterPath"))
            val backdrop = posterUrl(detail.optStr("backdropPath")?.replace("w300", "w1280"))
                ?: posterUrl(detail.optStr("backdropPath"))
            val plot = detail.optStr("overview")
            val year = parseYear(detail.optStr("releaseDate") ?: detail.optStr("firstAirDate"))
            val tags = detail.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    try { arr.getJSONObject(it).optStr("name") } catch (_: Exception) { null }
                }
            }
            val actors = detail.optJSONArray("cast")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    try { arr.getJSONObject(it).optStr("name") } catch (_: Exception) { null }
                }
            }
            val trailer = detail.optStr("trailerUrl")
            val rating = detail.optDouble("voteAverage", Double.NaN).takeIf { !it.isNaN() }?.let { (it * 1000).toInt() }
            val duration = detail.optInt("runtime", 0).takeIf { it > 0 }

            // data untuk loadLinks: "movie|<slug>"
            val data = "movie|$slug"
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.duration = duration
                if (!actors.isNullOrEmpty()) addActors(actors)
                addTrailer(trailer)
            }
        } else {
            // ---- SERIES ----
            val detail = apiGetJson("/api/series/$slug", referer = url)
                ?: throw ErrorLoadingException("Gagal load detail series.")
            val title = detail.optStr("title") ?: slug
            val poster = posterUrl(detail.optStr("posterPath"))
            val plot = detail.optStr("overview")
            val year = parseYear(detail.optStr("firstAirDate") ?: detail.optStr("releaseDate"))
            val tags = detail.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    try { arr.getJSONObject(it).optStr("name") } catch (_: Exception) { null }
                }
            }
            val actors = detail.optJSONArray("cast")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    try { arr.getJSONObject(it).optStr("name") } catch (_: Exception) { null }
                }
            }
            val trailer = detail.optStr("trailerUrl")
            val rating = detail.optDouble("voteAverage", Double.NaN).takeIf { !it.isNaN() }?.let { (it * 1000).toInt() }

            // VALIDATED via proxy 2026-10-07:
            // /api/series/{slug} -> seasons[] TANPA episodes (hanya seasonNumber, name, episodeCount)
            // episodes lengkap ada di /api/series/{slug}/season/{S} -> season.episodes[{id, episodeNumber, name, overview, stillPath}]
            val episodes = ArrayList<Episode>()
            val seasonsArr = detail.optJSONArray("seasons")
            val seasonNums = ArrayList<Int>()
            if (seasonsArr != null) {
                for (s in 0 until seasonsArr.length()) {
                    try {
                        val seasonObj = seasonsArr.getJSONObject(s)
                        seasonNums.add(seasonObj.optInt("seasonNumber", s + 1))
                    } catch (_: Exception) {}
                }
            }
            if (seasonNums.isEmpty()) seasonNums.add(1)

            for (seasonNum in seasonNums) {
                try {
                    val sJson = apiGetJson("/api/series/$slug/season/$seasonNum", referer = url)
                    val epsArr = sJson?.optJSONObject("season")?.optJSONArray("episodes")
                        ?: continue
                    for (e in 0 until epsArr.length()) {
                        try {
                            val epObj = epsArr.getJSONObject(e)
                            val epNum = epObj.optInt("episodeNumber", e + 1)
                            val epTitle = epObj.optStr("name") ?: epObj.optStr("title")
                            val epPlot = epObj.optStr("overview")
                            val still = posterUrl(epObj.optStr("stillPath"))
                            val epData = "series|$slug|$seasonNum|$epNum"
                            episodes.add(
                                newEpisode(epData) {
                                    this.name = if (!epTitle.isNullOrBlank()) "E$epNum: $epTitle" else "Episode $epNum"
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = still ?: poster
                                    this.plot = epPlot
                                }
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                if (!actors.isNullOrEmpty()) addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // ---------- STREAM (kualitas + subtitle) ----------
    // Alur reverse dari IDLIX-API (annurdien):
    // 1. GET /api/movies/{slug} -> UUID  ATAU  GET /api/series/{slug}/season/{S} -> episode UUID
    // 2. POST /api/views/track
    // 3. GET /api/watch/play-info/movie|episode/{uuid} -> gateToken, unlockAt, serverNow
    // 4. delay(unlockAt - serverNow) ~15 detik
    // 5. POST /api/watch/session/claim {gateToken} -> claim, redeemUrl
    // 6. POST redeemUrl (majorplay.net) {claim} content-type text/plain -> {url(m3u8), subtitles[]}

    private data class PlayInfo(
        val gateToken: String,
        val waitMs: Long
    )

    private data class ClaimResult(
        val claim: String,
        val redeemUrl: String
    )

    private data class StreamConfig(
        val streamUrl: String,
        val subtitles: List<SubInfo>
    )

    private data class SubInfo(
        val lang: String,
        val label: String,
        val url: String
    )

    private suspend fun resolveMovieUuid(slug: String, referer: String): String? {
        val json = apiGetJson("/api/movies/$slug", referer) ?: return null
        // response bisa {id: uuid} atau {data:{id}}
        json.optStr("id")?.let { return it }
        json.optJSONObject("data")?.optStr("id")?.let { return it }
        return null
    }

    private suspend fun resolveEpisodeUuid(
        slug: String,
        season: Int,
        episode: Int,
        referer: String
    ): String? {
        val json = apiGetJson("/api/series/$slug/season/$season", referer) ?: return null
        val eps = json.optJSONObject("season")?.optJSONArray("episodes") ?: return null
        for (i in 0 until eps.length()) {
            try {
                val o = eps.getJSONObject(i)
                if (o.optInt("episodeNumber", -1) == episode) {
                    return o.optStr("id")
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun trackView(
        contentType: String,
        contentId: String,
        episodeId: String?,
        referer: String
    ) {
        try {
            val body = mutableMapOf<String, Any?>(
                "contentType" to contentType,
                "contentId" to contentId
            )
            if (episodeId != null) body["episodeId"] = episodeId
            apiPostJson("/api/views/track", body, referer)
        } catch (_: Exception) {}
    }

    private suspend fun getPlayInfo(
        type: String, // "movie" | "episode"
        uuid: String,
        referer: String
    ): PlayInfo? {
        val json = apiGetJson("/api/watch/play-info/$type/$uuid", referer) ?: return null
        if (json.optStr("kind") != "gate") return null
        val gateToken = json.optStr("gateToken") ?: return null
        val unlockAt = json.optLong("unlockAt", 0L)
        val serverNow = json.optLong("serverNow", System.currentTimeMillis())
        val wait = (unlockAt - serverNow).coerceAtLeast(0L).coerceAtMost(20000L)
        return PlayInfo(gateToken, wait)
    }

    private suspend fun claimSession(gateToken: String, referer: String): ClaimResult? {
        val json = apiPostJson(
            "/api/watch/session/claim",
            mapOf("gateToken" to gateToken),
            referer
        ) ?: return null
        val claim = json.optStr("claim") ?: return null
        val redeemUrl = json.optStr("redeemUrl") ?: return null
        return ClaimResult(claim, redeemUrl)
    }

    private suspend fun redeemClaim(
        redeemUrl: String,
        claim: String,
        referer: String
    ): StreamConfig? {
        return try {
            // majorplay.net tidak dilindungi Cloudflare, bisa direct fetch.
            // Di browser harus text/plain biar lolos CORS, di Android pakai json biasa juga OK.
            // Kita kirim json {claim: ...} dengan header text/plain sesuai aslinya.
            val res = app.post(
                redeemUrl,
                headers = baseHeaders(referer) + mapOf("Content-Type" to "text/plain"),
                json = mapOf("claim" to claim),
                timeout = 20
            )
            if (!res.isSuccessful) return null
            val json = JSONObject(res.text)
            if (json.optStr("code") != "ok") return null
            val streamUrl = json.optStr("url") ?: return null
            val subs = ArrayList<SubInfo>()
            val subArr = json.optJSONArray("subtitles")
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    try {
                        val s = subArr.getJSONObject(i)
                        val u = s.optStr("path") ?: s.optStr("url") ?: continue
                        val lang = s.optStr("lang") ?: "id"
                        val label = s.optStr("label") ?: lang
                        subs.add(SubInfo(lang, label, fixUrl(u)))
                    } catch (_: Exception) {}
                }
            }
            StreamConfig(streamUrl, subs)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: "movie|<slug>" atau "series|<slug>|<season>|<episode>"
        val parts = data.split("|")
        if (parts.isEmpty()) return false

        val isMovie = parts[0] == "movie"
        val slug = parts.getOrNull(1) ?: return false

        val referer: String
        val playType: String
        val uuid: String?

        if (isMovie) {
            referer = "$mainUrl/movie/$slug"
            uuid = resolveMovieUuid(slug, referer) ?: return false
            trackView("movie", uuid, null, referer)
            playType = "movie"
        } else {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: 1
            referer = "$mainUrl/series/$slug/season/$season/episode/$episode"
            // butuh seriesUuid + episodeUuid
            val seasonJson = apiGetJson("/api/series/$slug/season/$season", referer)
            var seriesUuid: String? = null
            var epUuid: String? = null
            try {
                seriesUuid = seasonJson?.optJSONObject("series")?.optStr("id")
                    ?: seasonJson?.optStr("seriesId")
                val eps = seasonJson?.optJSONObject("season")?.optJSONArray("episodes")
                if (eps != null) {
                    for (i in 0 until eps.length()) {
                        val o = eps.getJSONObject(i)
                        if (o.optInt("episodeNumber", -1) == episode) {
                            epUuid = o.optStr("id")
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
            if (epUuid == null) {
                epUuid = resolveEpisodeUuid(slug, season, episode, referer) ?: return false
            }
            uuid = epUuid
            trackView("tv_series", seriesUuid ?: uuid, uuid, referer)
            playType = "episode"
        }

        // Step 3: play-info
        val playInfo = getPlayInfo(playType, uuid, referer) ?: return false

        // Step 4: delay wajib ~15 detik (anti-scraping Idlix)
        if (playInfo.waitMs > 0) {
            delay(playInfo.waitMs + 500)
        }

        // Step 5: claim
        val claimRes = claimSession(playInfo.gateToken, referer) ?: return false

        // Step 6: redeem ke majorplay
        val stream = redeemClaim(claimRes.redeemUrl, claimRes.claim, referer) ?: return false
        val streamUrl = fixUrl(stream.streamUrl)

        // ---- SUBTITLE (CC) ----
        // Cloudstream otomatis tampilkan pilihan CC dari sini
        stream.subtitles.forEach { s ->
            try {
                // Prioritaskan Indonesia, tapi kirim semua biar bisa pilih
                val label = when {
                    s.lang.equals("id", true) || s.label.contains("indo", true) -> "Indonesia"
                    s.lang.equals("en", true) || s.label.contains("english", true) -> "English"
                    else -> s.label.ifBlank { s.lang }
                }
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = label,
                        url = fixUrl(s.url)
                    )
                )
            } catch (_: Exception) {}
        }

        // ---- KUALITAS ----
        // Kalau m3u8 master, generateM3u8 akan memecah jadi 360p/480p/720p/1080p otomatis
        // sehingga muncul pilihan kualitas di player.
        if (streamUrl.contains(".m3u8")) {
            try {
                M3U8Helper.generateM3u8(
                    source = name,
                    streamUrl = streamUrl,
                    referer = "$mainUrl/",
                    headers = baseHeaders(referer)
                ).forEach { link ->
                    callback.invoke(link)
                }
                return true
            } catch (_: Exception) {
                // fallback ke single link di bawah
            }
        }

        // Fallback: single link (tetap bisa play, kualitas Auto)
        // Coba tebak kualitas dari URL, kalau tidak ketemu pakai 720
        val q = getQualityFromName(
            when {
                streamUrl.contains("1080", true) -> "1080p"
                streamUrl.contains("720", true) -> "720p"
                streamUrl.contains("480", true) -> "480p"
                streamUrl.contains("360", true) -> "360p"
                else -> "720p"
            }
        ) ?: Qualities.P720.value

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name",
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = q
                this.headers = baseHeaders(referer)
            }
        )

        return true
    }
}
