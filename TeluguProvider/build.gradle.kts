plugins {
    id("com.android.library")
    id("kotlin-android")
    id("cloudstream.plugin")
}

cloudstream {
    setAuthor("Shekar")
    description = "Telugu movies and series provider"
    language = "te"
    tvTypes = listOf("Movie")
    version = 1
}
