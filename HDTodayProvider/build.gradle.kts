// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "HDToday / GoTVSeries – TV Shows using TMDB IDs and VidSrc embeds"
    authors = listOf("CloudStream")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Currently only TMDB is supported. See
    // https://recloudstream.github.io/csdocs/library/
    tvTypes = listOf(
        "TvSeries",
        "Anime",
    )

    iconUrl = "https://gotvseries.top/images/favicon.png"
}
