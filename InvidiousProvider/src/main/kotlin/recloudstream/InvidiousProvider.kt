package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * NGEFILM21 — CloudStream 3 Extension
 * Domain: https://new37.ngefilm.site/
 * CMS: WordPress (Theme: Muvipro/GMV)
 *
 * Berdasarkan dokumentasi ngefilm-selectors.md
 */
class NgefilmProvider : MainAPI() {
    override var mainUrl = "https://new37.ngefilm.site"
    override var name = "Ngefilm21"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    // ═══════════════════════════════════════════════════════════════
    //  HOMEPAGE
    // ═══════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "1" to "Film Terbaru",
        "2" to "Series Terbaru",
        "3" to "Drama Korea",
        "4" to "Trending",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("article").mapNotNull { it.toSearchResult(this) }

        return newHomePageResponse(
            listOf(
                HomePageList(request.name, items, true)
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.get(
            "$mainUrl/",
            params = mapOf(
                "s" to query,
                "post_type[]" to "post",
                "post_type[]" to "tv"
            )
        ).document

        val results = doc.select("article").mapNotNull { it.toSearchResult(this) }
        return results.toNewSearchResponseList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD — Detail Film / Series
    // ═══════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val page = doc.selectFirst(".gmr-box-content.gmr-single")
            ?: doc.selectFirst(".site-content")
            ?: doc

        val poster = page.selectFirst("img.wp-post-image, img[src*='ngefilm'], img[src]")?.attr("src") ?: ""

        val title = page.selectFirst("h1.entry-title")?.text()
            ?: page.selectFirst("title")?.text()?.substringBefore(" -")?.trim()
            ?: return null

        val year = page.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
        val genres = page.select("a[href*='/Genre/']").mapNotNull { it.text().trim().ifEmpty { null } }
        val tags = page.select("a[href*='/tag/']").mapNotNull { it.text().trim().ifEmpty { null } }
        val cast = page.select("a[href*='/cast/']").mapNotNull { it.text().trim().ifEmpty { null } }
        val plot = page.selectFirst(".entry-content.entry-content-single")?.text()
            ?: page.selectFirst(".entry-content")?.text() ?: ""

        // Duration: parse menit dari teks
        val durationText = page.selectFirst(".gmr-duration-item")?.text()
            ?: page.selectFirst("span[class*='duration']")?.text()
        val duration = Regex("""(\d+)""").find(durationText ?: "")?.groupValues?.get(1)?.toIntOrNull()

        val trailer = page.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val allTags = genres + tags.filter { it !in genres }

        val isTvSeries = url.contains("/tv/") || url.contains("-season-")

        return if (isTvSeries) {
            loadTvSeries(url, page, title, poster, year, allTags, cast, plot, duration, trailer)
        } else {
            loadMovie(url, page, title, poster, year, allTags, cast, plot, duration, trailer)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD MOVIE
    // ═══════════════════════════════════════════════════════════════

    private suspend fun loadMovie(
        url: String,
        page: Element,
        title: String,
        poster: String,
        year: Int?,
        tags: List<String>,
        cast: List<String>,
        plot: String,
        duration: Int?,
        trailer: String?
    ): LoadResponse {
        val sources = mutableListOf<String>()

        // Server tabs: Server 1 — Server 4
        val serverTabs = page.select(".muvipro-player-tabs a")
        if (serverTabs.isNotEmpty()) {
            serverTabs.forEach { tab ->
                val playerUrl = tab.attr("href")
                val serverName = tab.text().trim()
                if (playerUrl.isNotBlank() && serverName.isNotBlank()) {
                    sources.add(
                        if (playerUrl.startsWith("?")) "$url$playerUrl" else fixUrl(playerUrl)
                    )
                }
            }
        }

        // Fallback: iframe
        if (sources.isEmpty()) {
            val iframe = page.selectFirst(".gmr-embed-responsive iframe")
            val iframeSrc = iframe?.attr("data-litespeed-src")
                ?: iframe?.attr("src")
            if (!iframeSrc.isNullOrBlank() && iframeSrc != "about:blank") {
                sources.add(fixUrl(iframeSrc))
            }
        }

        // Fallback: halaman itu sendiri
        if (sources.isEmpty()) sources.add(url)

        return newMovieLoadResponse(title, url, TvType.Movie, sources) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.duration = duration
            this.actors = cast.map { ActorData(Actor(it, "")) }
            // trailer tidak ditambahkan karena API baru tidak mendukung addTrailer
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD TV SERIES
    // ═══════════════════════════════════════════════════════════════

    private suspend fun loadTvSeries(
        url: String,
        page: Element,
        title: String,
        poster: String,
        year: Int?,
        tags: List<String>,
        cast: List<String>,
        plot: String,
        duration: Int?,
        trailer: String?
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seasonNum = Regex("""-season-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Strategi 1: AJAX Muvipro
        val postId = page.selectFirst("article")?.attr("id")?.removePrefix("post-")
            ?: page.selectFirst("[data-post-id]")?.attr("data-post-id")

        if (postId != null) {
            try {
                val epResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_get_episode",
                        "post_id" to postId,
                        "season" to seasonNum.toString()
                    ),
                    referer = url
                ).document

                epResponse.select("a[href]").forEachIndexed { index, el ->
                    val epUrl = fixUrl(el.attr("href"))
                    val epTitle = el.text().trim().ifEmpty { "Episode ${index + 1}" }
                    val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = seasonNum
                        }
                    )
                }
            } catch (_: Exception) { }
        }

        // Strategi 2: Link di halaman
        if (episodes.isEmpty()) {
            page.select("a[href*='/episode/'], a[href*='/tv/']").forEachIndexed { index, el ->
                val epUrl = el.attr("href")
                if (epUrl.isNotBlank() && !epUrl.contains("#") && !epUrl.startsWith("javascript")) {
                    val epTitle = el.text().trim()
                    val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    episodes.add(
                        newEpisode(fixUrl(epUrl)) {
                            this.name = epTitle.ifEmpty { "Episode $epNum" }
                            this.episode = epNum
                            this.season = seasonNum
                        }
                    )
                }
            }
        }

        // Strategi 3: Fallback
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Season $seasonNum"
                    this.episode = 1
                    this.season = seasonNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.duration = duration
            this.actors = cast.map { ActorData(Actor(it, "")) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD LINKS — Video dari Iframe/Player
    // ═══════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val targetUrl = if (data.startsWith("?")) fixUrl(data) else data
        val doc = app.get(targetUrl, referer = mainUrl).document

        // Strategi 1: Iframe
        found = tryExtractIframe(doc, subtitleCallback, callback) || found

        // Strategi 2: Server tabs
        if (!found) {
            val serverTabs = doc.select(".muvipro-player-tabs a")
            for (tab in serverTabs) {
                val href = tab.attr("href")
                if (href.startsWith("?player=")) {
                    try {
                        val tabDoc = app.get("$mainUrl$href", referer = targetUrl).document
                        found = tryExtractIframe(tabDoc, subtitleCallback, callback) || found
                    } catch (_: Exception) { }
                    if (found) break
                }
            }
        }

        // Strategi 3: AJAX admin-ajax
        if (!found) found = tryExtractAjax(doc, subtitleCallback, callback) || found

        // Strategi 4: Script tags
        if (!found) found = tryExtractFromScripts(doc, subtitleCallback, callback) || found

        // Strategi 5: Fallback URL langsung
        if (!found) found = loadExtractor(targetUrl, subtitleCallback, callback)

        return found
    }

    // ═══════════════════════════════════════════════════════════════
    //  EKSTRAKTOR
    // ═══════════════════════════════════════════════════════════════

    private suspend fun tryExtractIframe(
        doc: Element,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframes = doc.select("iframe")
            .filter { it.attr("src") != "about:blank" || it.hasAttr("data-litespeed-src") }

        for (iframe in iframes) {
            val src = iframe.attr("data-litespeed-src")
                .ifEmpty { iframe.attr("src") }
                .ifEmpty { continue }
            if (src == "about:blank" || src.length < 10) continue
            if (loadExtractor(fixUrl(src), subtitleCallback, callback)) return true
        }
        return false
    }

    private suspend fun tryExtractAjax(
        doc: Element,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerData = doc.select("[data-id], [data-linkid]")
        for (el in playerData) {
            val id = el.attr("data-id").ifEmpty { el.attr("data-linkid") }
            if (id.isBlank()) continue

            try {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "muvipro_get_player", "id" to id),
                    referer = mainUrl
                ).text

                val iframeSrc = Regex("""src=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                if (!iframeSrc.isNullOrBlank() && loadExtractor(fixUrl(iframeSrc), subtitleCallback, callback)) {
                    return true
                }

                val directUrl = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""").find(response)?.groupValues?.get(1)
                if (!directUrl.isNullOrBlank()) {
                    callback(newExtractorLink(name, name, directUrl) {
                        referer = mainUrl
                        quality = if (directUrl.contains(".m3u8")) Qualities.Unknown.value else Qualities.Unknown.value
                    })
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }

    private suspend fun tryExtractFromScripts(
        doc: Element,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val scripts = doc.select("script")
        for (script in scripts) {
            val html = script.html()
            if (html.isBlank()) continue

            val patterns = listOf(
                Regex("""(?:url|src|player|embed|file)\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""(https?://[^"'\s]+player[^"'\s]*)"""),
                Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)"""),
            )

            for (pattern in patterns) {
                val match = pattern.find(html) ?: continue
                val videoUrl = match.groupValues[1]
                if (videoUrl.contains("http") &&
                    !videoUrl.contains("google.com") &&
                    !videoUrl.contains("recaptcha") &&
                    !videoUrl.contains("facebook.com")
                ) {
                    if (loadExtractor(fixUrl(videoUrl), subtitleCallback, callback)) return true
                }
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mapping kualitas (String) → Qualities (Int)
     */
    private fun parseQuality(value: String?): Int {
        if (value == null) return Qualities.Unknown.value
        return when {
            value.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            value.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            value.contains("720", ignoreCase = true) ||
                value.contains("HD", ignoreCase = true) -> Qualities.P720.value
            value.contains("480", ignoreCase = true) ||
                value.contains("DVD", ignoreCase = true) -> Qualities.P480.value
            value.contains("360", ignoreCase = true) -> Qualities.P360.value
            value.contains("CAM", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /**
     * Konversi elemen HTML artikel (grid) → SearchResponse
     */
    private fun Element.toSearchResult(provider: NgefilmProvider): SearchResponse? {
        val linkEl = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val title = this.selectFirst("h2, h3")?.text()
            ?: linkEl.text().trim()
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = img?.attr("src")
            ?: img?.attr("data-src")
            ?: img?.attr("data-lazy-src")
            ?: ""

        val year = this.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
        val qualityStr = this.selectFirst("a[href*='/quality/']")?.text()
        val quality = parseQuality(qualityStr)

        val isTvSeries = href.contains("/tv/") || this.`is`("article[class*='tv']")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        }
    }
