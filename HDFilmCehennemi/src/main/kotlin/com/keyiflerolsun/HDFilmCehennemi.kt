package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            if (response.code in listOf(403, 503)) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            val bodyString = response.peekBody(1024 * 1024).string()
            if (bodyString.contains("Just a moment") || bodyString.contains("Güvenlik taraması") || bodyString.contains("challenges.cloudflare.com")) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    // ObjectMapper for JSON parsing
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // Standard headers for requests
    private val standardHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // Ana sayfa kategorilerini doğrudan sitenin gerçek linkleriyle tanımlıyoruz
    override val mainPage = mainPageOf(
        "${mainUrl}/"                                                     to "Yeni Eklenen Filmler",
        "${mainUrl}/category/tavsiye-filmler-izle2/"                     to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler-2/"                         to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-2/"                              to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle-4/"                    to "En Çok Beğenilenler",
        "${mainUrl}/yabancidiziizle-5/"                                   to "Yeni Eklenen Diziler",
        "${mainUrl}/category/film-izle-2/"                                to "Tüm Filmler",
        "${mainUrl}/tur/aile-filmleri-izleyin-6/"                         to "Aile",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-5/"                      to "Aksiyon",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-5/"                  to "Animasyon",
        "${mainUrl}/tur/belgesel-filmlerini-izle-1/"                      to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-3/"                to "Bilim Kurgu",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-1/"                     to "Komedi",
        "${mainUrl}/tur/korku-filmlerini-izle-4/"                         to "Korku",
        "${mainUrl}/tur/romantik-filmleri-izle-2/"                        to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page == 1) "$base/" else "$base/page/$page/"

        val document = app.get(url, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document
        val elements = document.select("a.poster, div.slider-slide a, div.posters-4-col a, div.section-content a.story")
        val results = elements.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        Log.d("HDCH", "Kategori ${request.name} için ${results.size} sonuç bulundu ($url)")
        return newHomePageResponse(request.name, results)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.poster-title, h3.story-title, .poster-title, .title, h4, h3, h2")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (title.contains("Seri Filmler", ignoreCase = true) ||
            title.contains("Japonya Filmleri", ignoreCase = true) ||
            title.contains("Kore Filmleri", ignoreCase = true) ||
            title.contains("Hint Filmleri", ignoreCase = true) ||
            title.contains("Türk Filmleri", ignoreCase = true) ||
            title.contains("DC Yapımları", ignoreCase = true) ||
            title.contains("Marvel Yapımları", ignoreCase = true) ||
            title.contains("Amazon Yapımları", ignoreCase = true) ||
            title.contains("1080p Film izle", ignoreCase = true) ||
            title.contains("Tüm Fragmanlar", ignoreCase = true)) {
            return null
        }

        val href = fixUrlNull(this.attr("href"))
            ?: fixUrlNull(this.selectFirst("a")?.attr("href"))
            ?: return null

        if (href == mainUrl || href == "$mainUrl/" || href.contains("/category/") || href.contains("/tur/") || href.contains("/yil/")) {
            return null
        }

        // data-src gerçek resim URL'sini içerir (src bazen base64 placeholder olur)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src")?.takeUnless { it.startsWith("data:") })

        val cleanTitle = title.replace(" izle", "").trim()

        return newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val response = app.get(
                "${mainUrl}/search?q=${query}",
                headers = mapOf("X-Requested-With" to "fetch"),
                referer = "${mainUrl}/",
                interceptor = interceptor
            )
            if (response.text.trim().startsWith("{")) {
                val resultsJson: Results = objectMapper.readValue(response.text)
                resultsJson.results.forEach { resultHtml ->
                    val document = Jsoup.parse(resultHtml)
                    val title = document.selectFirst("h4.title, .title, a")?.text()?.trim() ?: return@forEach
                    val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
                    val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("data-src"))
                        ?: fixUrlNull(document.selectFirst("img")?.attr("src")?.takeUnless { it.startsWith("data:") })

                    searchResults.add(
                        newMovieSearchResponse(title.replace(" izle", "").trim(), href, TvType.Movie) {
                            this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                        }
                    )
                }
            } else {
                val document = response.document
                val items = document.select("a.poster, div.poster a, div.posters-4-col a, a")
                items.mapNotNull { it.toSearchResult() }.forEach { searchResults.add(it) }
            }
        } catch (e: Exception) {
            Log.e("HDCH", "Search error: ${e.message}")
        }
        return searchResults.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document

        val title = document.selectFirst("h1.section-title, h1.title, h1, [property='og:title']")?.let { 
            if (it.hasAttr("content")) it.attr("content") else it.text() 
        }?.substringBefore(" izle")?.trim() ?: return null

        val poster = fixUrlNull(document.selectFirst("aside.post-info-poster img, [property='og:image']")?.let {
            if (it.hasAttr("content")) it.attr("content") else (it.attr("data-src").ifEmpty { it.attr("src") })
        })

        val tags        = document.select("div.post-info-genres a, div.genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a, span.year")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons, div.seasons-tab-content").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p, article.content p, p.description")?.text()?.trim()
        val rating      = document.selectFirst("div.post-info-imdb-rating span, span.imdb")?.text()?.replace(",", ".")?.substringBefore("(")?.trim()?.toDoubleOrNull()
        val actors      = document.select("div.post-info-cast a, div.cast a").mapNotNull {
            val actorName = it.selectFirst("strong, span")?.text()?.trim() ?: return@mapNotNull null
            val actorImg = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
            Actor(actorName, actorImg)
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide a, a.poster").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button, button[data-modal*='trailer']")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4, span")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button, button[data-modal*='trailer']")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
//        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script    = app.get(url, referer = "${mainUrl}/").document.select("script")
            .find { it.data().contains("sources:") }?.data() ?: return

        Log.d("fix","urlne $url")

        val videoData = getAndUnpack(script).substringAfter("file_link=\"").substringBefore("\";")
//        val subData   = script.substringAfter("tracks: [").substringBefore("]")

//        Log.d("fix","subdata $subData")

        callback.invoke(
            newExtractorLink(
                source  = source,
                name    = source,
                url     = base64Decode(videoData),
                type    = INFER_TYPE
            ) {
                // DSL builder kullanarak referer ve quality ayarı
                this.referer = "${mainUrl}/"
                this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Norton/124.0.0.0")
                this.quality = Qualities.Unknown.value
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name","data = $data")
        val document = app.get(data, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document
        val iframealak = fixUrlNull(
            document.selectFirst(".close")?.attr("data-src")
                ?: document.selectFirst(".rapidrame")?.attr("data-src")
        ).toString()
        Log.d("kraptor_$name","iframealak = $iframealak")

        // Process hdfilmcehennemi.mobi subtitles
        if (iframealak.contains("hdfilmcehennemi.mobi")) {
            val iframedoc = app.get(iframealak, referer = mainUrl, interceptor = interceptor).document
            val baseUri = iframedoc.location().substringBefore("/", "https://www.hdfilmcehennemi.mobi")

            iframedoc.select("track[kind=captions]")
                .filter { it.attr("srclang") != "forced" }
                .forEach { track ->
                    val lang = track.attr("srclang").let {
                        when (it) {
                            "tr" -> "Turkish"
                            "en" -> "English"
                            "Türkçe" -> "Turkish"
                            "İngilizce" -> "English"
                            else -> it
                        }
                    }
                    val subUrl = track.attr("src").let { src ->
                        if (src.startsWith("http")) src else "$baseUri/$src".replace("//", "/")
                    }
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
        } else if (iframealak.contains("rplayer")) {
            val iframeDoc = app.get(iframealak, referer = "$data/", interceptor = interceptor).document
//            Log.d("kraptor_$name","iframeDoc = $iframeDoc")
            val regex = Regex("\"file\":\"((?:[^\"]|\"\")*)\"", options = setOf(RegexOption.IGNORE_CASE))
            val matches = regex.findAll(iframeDoc.toString())

            for (match in matches) {
                val fileUrlEscaped = match.groupValues[1]
                val fileUrl = fileUrlEscaped.replace("\\/", "/")
                val tamUrl = fixUrlNull(fileUrl).toString()
                val sonUrl = "${tamUrl}/"
                val langCode = when {
                    fileUrl.contains("Turkish", ignoreCase = true) -> "Turkish"
                    fileUrl.contains("English", ignoreCase = true) -> "English"
                    else -> "Unknown"
                }
                subtitleCallback.invoke(SubtitleFile(lang = langCode, url = sonUrl))
            }
        }


        Log.d("kraptor_$name", "iframegeldi mi $iframealak")

        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf(
                        "Content-Type"     to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data,
                    interceptor = interceptor
                ).text


                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: Regex("""data-src="([^"]+)"""").find(apiGet)?.groupValues?.get(1)
                    ?: return@forEach

                Log.d("kraptor_$name", "iframe mi $iframe")

                val iframeGet = app.get(iframe, referer = "${mainUrl}/", interceptor = interceptor).text

                val evalRegex = Regex("""eval\((.*?\\.*?\\.*?\\.*?\{\}\)\))""", RegexOption.DOT_MATCHES_ALL)
                val packedCode = evalRegex.find(iframeGet)?.value
                val unpackedJs = if (packedCode != null) JsUnpacker(packedCode).unpack().toString() else iframeGet

                val regex = Regex("""dc_hello\("([^"]+)"\)""")
                val match = regex.find(unpackedJs)
                val base64String = match?.groupValues?.get(1) ?: return@forEach
                Log.d("kraptor_$name", "base64String $base64String")
                val realUrl = dcHello(base64String)
                Log.d("kraptor_$name", "realUrl $realUrl")



                if (iframe.contains("?rapidrame_id=")) {
                    iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                }

                val videoIsim = if (realUrl.contains("rapidrame")) {
                    "Rapidrame"
                } else if (realUrl.contains("cdnimages")) {
                    "Close"
                } else {
                    "HDFilmCehennemi"
                }

                val referer = if (realUrl.contains("cdnimages")) {
                    "https://hdfilmcehennemi.mobi/"
                } else {
                    "${realUrl}/"
                }

                Log.d("kraptor_$name", "$source » $videoID » $iframe")
                callback.invoke(
                    newExtractorLink(
                        source = videoIsim,
                        name = videoIsim,
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }


    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )

    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: Boolean,
        @JsonProperty("keywords") val keywords: Boolean
    )
}

fun base64Decode(encoded: String): String {
    return try {
        String(Base64.decode(encoded.trim(), Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}

fun dcHello(encoded: String): String {
    // İlk Base64 çöz
    val firstDecoded = base64Decode(encoded)
    Log.d("kraptor_hdfilmcehennemi", "firstDecoded $firstDecoded")
    // Ters çevir
    val reversed = firstDecoded.reversed()
    Log.d("kraptor_hdfilmcehennemi", "reversed $reversed")
    // İkinci Base64 çöz
    val secondDecoded = base64Decode(reversed)

    val gercekLink    = if (secondDecoded.contains("+")) {
        secondDecoded.substringAfterLast("+")
    } else if (secondDecoded.contains(" ")) {
        secondDecoded.substringAfterLast(" ")
    } else if (secondDecoded.contains("|")){
        secondDecoded.substringAfterLast("|")
    } else {
        secondDecoded
    }
    Log.d("kraptor_hdfilmcehennemi", "secondDecoded $secondDecoded")
    return gercekLink
}