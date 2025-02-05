// Use an integer for version numbers
version = 2

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Watch livestreams from Twitch"
    authors = listOf("CranberrySoup")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=twitch.tv&sz=%size%"

    isCrossPlatform = true
}