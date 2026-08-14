package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JetFilmIzle : MainAPI() {
    override var mainUrl              = "https://jetizle.com"
    override var name                 = "Jet Film"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

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

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/turkce-dublaj-filmler/"  to "Türkçe Dublaj Filmler",
        "${mainUrl}/"                         to "Son Eklenenler",
        "${mainUrl}/dizi/aksiyon-filmleri/"   to "Aksiyon Filmleri",
        "${mainUrl}/dizi/komedi-filmleri/"    to "Komedi Filmleri",
        "${mainUrl}/dizi/korku-filmleri/"     to "Korku Filmleri",
        "${mainUrl}/dizi/bilim-kurgu-filmleri/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/dizi/animasyon-filmleri/" to "Animasyon Filmleri",
        "${mainUrl}/dizi/macera-filmleri/"    to "Macera Filmleri",
        "${mainUrl}/dizi/gerilim-filmleri/"   to "Gerilim Filmleri",
        "${mainUrl}/dizi/dram-filmleri/"      to "Dram Filmleri",
        "${mainUrl}/dizi/fantastik-filmleri/" to "Fantastik Filmleri",
        "${mainUrl}/dizi/yerli-filmleri/"     to "Yerli Filmler",
        "${mainUrl}/dizi/suc-filmleri/"       to "Suç Filmleri",
        "${mainUrl}/dizi/romantik-filmleri/"  to "Romantik Filmleri",
        "${mainUrl}/dizi/aile-filmleri/"      to "Aile Filmleri",
        "${mainUrl}/dizi/belgeseler-filmleri/" to "Belgesel Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page == 1) "$base/" else "$base/page/$page/"

        val document = app.get(url, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document
        val elements = document.select("div.listmovie div.movie-box")
        val results = elements.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, results)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.film-ismi a") ?: this.selectFirst("div.poster div.img a")
        val title = titleElement?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = fixUrlNull(titleElement?.attr("href"))
            ?: fixUrlNull(this.selectFirst("a")?.attr("href"))
            ?: return null

        val img = this.selectFirst("div.poster div.img img") ?: this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src"))
            ?: fixUrlNull(img?.attr("src")?.takeUnless { it.startsWith("data:") })

        val year = this.selectFirst("div.film-yil")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val cleanTitle = title.replace(" izle", "").trim()

        return newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/?s=${query}"
        val document = app.get(url, headers = standardHeaders, referer = "${mainUrl}/", interceptor = interceptor).document
        val elements = document.select("div.listmovie div.movie-box")
        return elements.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document

        val rawTitle = document.selectFirst("h1.title-border, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null

        val title = rawTitle.replace("Jet Film izle", "")
            .replace("Full HD TR Dublaj", "")
            .replace("Jet Film", "")
            .replace("izle", "")
            .replace("|", "")
            .trim()

        val poster = fixUrlNull(document.selectFirst("div.film-afis img, meta[property='og:image']")?.let {
            if (it.hasAttr("content")) it.attr("content") else (it.attr("data-src").ifEmpty { it.attr("src") })
        })

        val description = document.selectFirst("#film-aciklama")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val tags = document.select("#listelements a, div.elements a").map { it.text().trim() }
        val actors = document.select("div.list-item a[href*='/oyuncu/']").map {
            Actor(it.text().trim())
        }

        val recommendations = document.select("div.owl-carousel div.listmovie div.movie-box").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document

        val iframes = document.select("div.filmalani iframe, div.video-container iframe, div.video iframe, iframe")
        var foundAny = false

        for (iframe in iframes) {
            val src = iframe.attr("data-litespeed-src").ifEmpty {
                iframe.attr("data-src").ifEmpty {
                    iframe.attr("src")
                }
            }

            if (src.isBlank() || src == "about:blank" || src.contains("facebook.com") || src.contains("twitter.com")) {
                continue
            }

            val fixedUrl = fixUrl(src)
            Log.d("JetFilm", "Extractor iframe bulundu: $fixedUrl")

            loadExtractor(fixedUrl, data, subtitleCallback, callback)
            foundAny = true
        }

        return foundAny
    }
}
