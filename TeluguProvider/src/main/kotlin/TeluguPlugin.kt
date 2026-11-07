package com.shekar.telugu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TeluguPlugin : Plugin() {
    override fun load(context: Context) {
        // This registers your TeluguProvider into CloudStream
        registerMainAPI(TeluguProvider())
    }
}
