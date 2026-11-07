package com.shekar.telugu

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TeluguPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TeluguProvider())
    }
}
