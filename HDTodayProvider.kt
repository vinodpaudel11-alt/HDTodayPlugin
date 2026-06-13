package com.hdtoday

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

class HDTodayProvider : MainAPI() {

    override var mainUrl = "https://gotvseries.top"
    override var name = "HDToday"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
    )

    // ────────────────────────────────────────────────
    // MAIN PAGE  (home-page rows)
    // ────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/trending"         to "Trending",
        "$mainUrl/popular-tv-shows" to "Popular TV Shows",
        "$mainUrl/top-rated"        to "Top Rated",
        "$mainUrl/action-adventure" to "Action & Adventure",
        "$mainUrl/sci-fi"           to "Sci-Fi & Fantasy",
        "$mainUrl/horror"           to "Horror",
        "$mainUrl/comedy-gold"      to "Comedy",
        "$mainUrl/drama-series"     to "Drama",
        "$mainUrl/anime"            to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The site uses simple paginated URLs:  /trending?page=2
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url).document

        val items = doc.select("div.film_list-wrap article.film_list-wrap, " +
                               "div.flw-item, " +
                               "div.film-poster, " +
                               "a[href*='/tv/']").mapNotNull { el ->
            toSearchResult(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ────────────────────────────────────────────────
    // HELPERS: parse a card element → SearchResponse
    // ────────────────────────────────────────────────
    private fun toSearchResult(el: Element): SearchResponse? {
        // Grab the anchor that links to /tv/{id}-{slug}
        val anchor = el.selectFirst("a[href*='/tv/']") ?: return null
        val href   = anchor.attr("href").trim()

        // We need the detail page URL, not the watch URL
        val detailUrl = if (href.contains("/watch/")) {
            // strip /watch/{id}-{slug}/season-x/episode-y  →  /tv/{id}-{slug}
            val match = Regex("(/tv/\\d+[^/]*)").find(href)?.groupValues?.get(1)
                ?: return null
            "$mainUrl$match"
        } else {
            if (href.startsWith("http")) href else "$mainUrl$href"
        }

        val title = el.selectFirst("h2, h3, .film-name, img")
            ?.let { if (it.tagName() == "img") it.attr("alt") else it.text() }
            ?.trim() ?: return null

        val poster = el.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ────────────────────────────────────────────────
    // SEARCH
    // ────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${query.encodeURL()}").document
        return doc.select("a[href*='/tv/']").mapNotNull { el ->
            toSearchResult(el)
        }.distinctBy { it.url }
    }

    // ────────────────────────────────────────────────
    // LOAD  (detail page → seasons/episodes)
    // ────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title   = doc.selectFirst("h1, h2.film-name")?.text()?.trim() ?: return null
        val poster  = doc.selectFirst("div.film-poster img, img.film-poster")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot    = doc.selectFirst("div.description, p.description, .detail-desc")?.text()?.trim()
        val rating  = doc.selectFirst(".film-stats .item:contains(/10), .score")?.text()
            ?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()?.times(10)?.toInt()

        // Extract TMDB ID from URL  e.g.  /tv/312493-viral-hit-2026
        val tmdbId  = Regex("/(\\d+)-").find(url)?.groupValues?.get(1)

        // Build seasons/episodes from the watch page links
        // Navigate to the first episode watch page to grab full episode list
        val watchBase = if (tmdbId != null) {
            val slug = url.substringAfterLast("/tv/")
            "$mainUrl/tv/watch/$slug/season-1/episode-1/"
        } else null

        val episodes = mutableListOf<Episode>()

        if (watchBase != null) {
            val watchDoc = app.get(watchBase).document
            // Season selector
            val seasonLinks = watchDoc.select("div.seasons-list a, select.season option, ul.seasons li a")
            val seasonNumbers = if (seasonLinks.isEmpty()) listOf(1)
            else seasonLinks.mapNotNull { it.attr("href").let { h ->
                Regex("season-(\\d+)").find(h)?.groupValues?.get(1)?.toIntOrNull()
            }}.distinct().sorted()

            for (season in seasonNumbers) {
                // Load that season's episode list
                val seasonUrl = watchBase.replace("season-1", "season-$season")
                val sDoc = if (season == 1) watchDoc else app.get(seasonUrl).document

                val epLinks = sDoc.select("div.ss-list a[href*='/episode-'], ul.episodes a[href*='episode']")
                if (epLinks.isEmpty()) {
                    // fallback: just add episode 1
                    val epUrl = "$mainUrl/tv/watch/${url.substringAfterLast("/tv/")}/season-$season/episode-1/"
                    episodes.add(newEpisode(epUrl) {
                        this.season  = season
                        this.episode = 1
                    })
                } else {
                    epLinks.forEach { epEl ->
                        val epHref   = epEl.attr("href")
                        val epNum    = Regex("episode-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val epFullUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                        episodes.add(newEpisode(epFullUrl) {
                            this.season  = season
                            this.episode = epNum
                            this.name    = epEl.attr("title").ifBlank { epEl.text().trim().ifBlank { null } }
                        })
                    }
                }
            }
        }

        // If we got nothing, fake at least S1E1
        if (episodes.isEmpty() && tmdbId != null) {
            val slug = url.substringAfterLast("/tv/")
            episodes.add(newEpisode("$mainUrl/tv/watch/$slug/season-1/episode-1/") {
                this.season  = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl   = poster
            this.plot        = plot
            this.rating      = rating
        }
    }

    // ────────────────────────────────────────────────
    // LOAD LINKS  (watch page → video URLs)
    // ────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // The site embeds iframes.  Primary embed pattern found:
        // https://vidbox.casa/player.php?play=https://vidsrc-embed.ru/embed/tv?tmdb=XXXX&season=Y&episode=Z
        // Also supports multiple server buttons (VidCore, Multi, VidFast, etc.)

        // 1. Grab direct iframe src
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")?.trim()

        // 2. Gather server buttons (each has a data-src or onclick with embed URL)
        val serverUrls = mutableListOf<Pair<String, String>>() // name to url

        // Try to find server links in script tags or data attributes
        doc.select("div.servers-list a, ul.server-list li a, button[data-src]").forEach { el ->
            val serverName = el.text().trim().ifBlank { "Server" }
            val src = el.attr("data-src").ifBlank { el.attr("href") }.trim()
            if (src.isNotBlank() && src.startsWith("http")) {
                serverUrls.add(serverName to src)
            }
        }

        // Extract TMDB ID + season + episode from the URL
        // URL format: /tv/watch/{tmdb}-{slug}/season-{s}/episode-{e}/
        val tmdbId  = Regex("/watch/(\\d+)-").find(data)?.groupValues?.get(1)
        val season  = Regex("season-(\\d+)").find(data)?.groupValues?.get(1) ?: "1"
        val episode = Regex("episode-(\\d+)").find(data)?.groupValues?.get(1) ?: "1"

        // Build standard vidsrc embed URLs using TMDB ID
        if (tmdbId != null) {
            val embedSources = listOf(
                "VidSrc"   to "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode",
                "VidSrc2"  to "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode",
                "VidSrc3"  to "https://vidsrc-embed.ru/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode",
                "VidBox"   to "https://vidbox.casa/player.php?play=https://vidsrc-embed.ru/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode",
                "2Embed"   to "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode",
                "Autoembed" to "https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode",
                "SmashyStream" to "https://player.smashy.stream/tv/$tmdbId?s=$season&e=$episode",
            )
            embedSources.forEach { (name, embedUrl) ->
                loadExtractor(embedUrl, data, subtitleCallback) { link ->
                    callback(
                        ExtractorLink(
                            source  = name,
                            name    = name,
                            url     = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            isM3u8  = link.isM3u8,
                            headers = link.headers,
                        )
                    )
                }
            }
        }

        // Also try extracting any iframe we found directly
        if (iframeSrc != null && iframeSrc.isNotBlank()) {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // Server buttons collected above
        serverUrls.forEach { (name, embedUrl) ->
            loadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        return true
    }

    // URL encode helper
    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "UTF-8")
}
