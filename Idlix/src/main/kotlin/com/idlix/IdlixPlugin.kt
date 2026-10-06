package com.idlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IdlixProvider())
    }
}
