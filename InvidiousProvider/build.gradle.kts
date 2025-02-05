// use an integer for version numbers
version = 8

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Watch content from any invidious instance"
    authors = listOf("Cloudburst")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=invidious.io&sz=%size%"

    isCrossPlatform = true
}