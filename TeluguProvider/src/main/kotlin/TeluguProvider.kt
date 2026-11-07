package com.shekar.telugu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TeluguProvider : MainProvider() {
    override var mainUrl = "https://www.5movierulz.supplies"
    override var name = "Telugu Provider"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "te"
    override val hasMainPage = true
}
