// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them.

    description = "Watch livestreams from Twitch"
    authors = listOf("CranberrySoup")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=twitch.tv&sz=%size%"
}