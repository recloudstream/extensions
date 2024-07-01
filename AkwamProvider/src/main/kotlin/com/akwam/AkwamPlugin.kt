package com.akwam
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AkwamPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Akwam())
    }
}