
package com.shekar.telugu

import com.lagradost.cloudstream3.*

class TeluguProvider : MainAPI() {

    override var mainUrl = "https://example.com"
    override var name = "Telugu Provider"
    override var lang = "te"

    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return newHomePageResponse(
            emptyList<HomePageList>(),
            false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        throw NotImplementedError(
            "No authorised content source configured yet."
        )
    }
}
