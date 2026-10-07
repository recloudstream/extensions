package com.ngefilm

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NgefilmPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(InvidiousProvider())
    }
}
