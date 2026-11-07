plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 3

cloudstream {
    description = "MovieRulz Telugu Provider"
    authors = listOf("Chandu")
    status = 1
    tvTypes = listOf("Movie")
    isCrossPlatform = true
}
