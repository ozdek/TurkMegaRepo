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
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class JetFilmIzle : MainAPI() {
    override var mainUrl              = "https://jetizle.co"
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

    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

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

        val trailer = document.selectFirst("iframe[src*='youtube'], iframe[data-litespeed-src*='youtube'], iframe[data-src*='youtube']")?.let {
            val src = it.attr("data-litespeed-src").ifEmpty { it.attr("data-src").ifEmpty { it.attr("src") } }
            fixUrlNull(src)
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
            addTrailer(trailer)
        }
    }

    private data class CryptoPayload(
        @JsonProperty("ct") val ct: String,
        @JsonProperty("iv") val iv: String,
        @JsonProperty("s")  val s: String
    )

    private data class SubtitleItem(
        @JsonProperty("file")     val file: String?,
        @JsonProperty("label")    val label: String?,
        @JsonProperty("language") val language: String?
    )

    private data class DecryptedVideo(
        @JsonProperty("video_location") val videoLocation: String?,
        @JsonProperty("title")          val title: String?,
        @JsonProperty("referer")        val referer: String?,
        @JsonProperty("dwlink")         val dwlink: String?,
        @JsonProperty("strSubtitles")   val strSubtitles: List<SubtitleItem>?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document
        var foundAny = false

        // Hem ana sayfadaki hem de alt sayfalardaki (örn. /2/ - Altyazılı) kaynakları tara
        val pagesToScan = mutableListOf(data)
        document.select(".filmplus_sources a.post-page-numbers, .sources a").forEach { a ->
            val subUrl = fixUrlNull(a.attr("href"))
            if (subUrl != null && !pagesToScan.contains(subUrl)) {
                pagesToScan.add(subUrl)
            }
        }

        for (pageUrl in pagesToScan) {
            val pageDoc = if (pageUrl == data) document else app.get(pageUrl, headers = standardHeaders, referer = mainUrl, interceptor = interceptor).document
            val langLabel = if (pageUrl.endsWith("/2/") || pageUrl.contains("altyazi")) "Türkçe Altyazılı" else "Türkçe Dublaj"

            val elements = pageDoc.select("div.filmalani iframe, div.video-container iframe, div.video iframe, iframe, video source, video")
            for (element in elements) {
                val src = element.attr("data-litespeed-src").ifEmpty {
                    element.attr("data-src").ifEmpty {
                        element.attr("src")
                    }
                }

                // Sosyal medya ve YouTube fragman iframe'lerini film kaynağı olarak yükleme
                if (src.isBlank() || src == "about:blank" || src.contains("facebook.com") || src.contains("twitter.com") || src.contains("youtube.com") || src.contains("youtu.be")) {
                    continue
                }

                val fixedUrl = fixUrl(src)
                Log.d("JetFilm", "Iframe inceleniyor ($langLabel): $fixedUrl")

                // Hotstream / bePlayer kontrolü
                if (fixedUrl.contains("hotstream.club") || fixedUrl.contains("hupload.pics") || fixedUrl.contains("/embed/")) {
                    try {
                        val iframeResponse = app.get(fixedUrl, headers = standardHeaders, referer = pageUrl, interceptor = interceptor)
                        val bePlayerRegex = Regex("""bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{.+?\})['"]\s*\)""", RegexOption.DOT_MATCHES_ALL)
                        val bePlayerRegexAlt = Regex("""bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*(\{.+?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
                        
                        val match = bePlayerRegex.find(iframeResponse.text) ?: bePlayerRegexAlt.find(iframeResponse.text)

                        if (match != null) {
                            val passB64 = match.groupValues[1]
                            val jsonStr = match.groupValues[2]
                            val payload: CryptoPayload = objectMapper.readValue(jsonStr)

                            val decryptedJson = decryptBePlayer(passB64, payload)
                            if (decryptedJson != null) {
                                val videoObj: DecryptedVideo = objectMapper.readValue(decryptedJson)
                                val videoLocation = videoObj.videoLocation

                                // Altyazıları ilet
                                videoObj.strSubtitles?.forEach { sub ->
                                    if (!sub.file.isNullOrBlank()) {
                                        val subUrl = fixUrl(sub.file)
                                        subtitleCallback(
                                            newSubtitleFile(
                                                lang = sub.label ?: "Turkish",
                                                url = subUrl
                                            )
                                        )
                                    }
                                }

                                if (!videoLocation.isNullOrBlank()) {
                                    val fullVideoLocation = fixUrl(videoLocation)

                                    // Master M3U8 listesini anlık çekerek tekil akış URL'lerini ekleyelim
                                    try {
                                        val m3u8Resp = app.get(
                                            fullVideoLocation,
                                            headers = mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                                "Referer" to fixedUrl,
                                                "X-Requested-With" to "XMLHttpRequest"
                                            ),
                                            referer = fixedUrl,
                                            interceptor = interceptor
                                        )
                                        val m3u8Text = m3u8Resp.text
                                        if (m3u8Text.contains("#EXTM3U")) {
                                            val lines = m3u8Text.lines()
                                            for (i in lines.indices) {
                                                val line = lines[i].trim()
                                                if (line.startsWith("#EXT-X-STREAM-INF")) {
                                                    val nextLine = lines.getOrNull(i + 1)?.trim()
                                                    if (nextLine != null && nextLine.startsWith("http")) {
                                                        val quality = if (line.contains("FULLHD") || line.contains("1080")) Qualities.P1080.value
                                                                      else if (line.contains("720")) Qualities.P720.value
                                                                      else if (line.contains("480")) Qualities.P480.value
                                                                      else Qualities.Unknown.value

                                                        callback(
                                                            newExtractorLink(
                                                                source = "JetFilm",
                                                                name = "JetFilm ($langLabel)",
                                                                url = nextLine,
                                                                type = ExtractorLinkType.M3U8
                                                            ) {
                                                                this.referer = fixedUrl
                                                                this.quality = quality
                                                            }
                                                        )
                                                        foundAny = true
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}

                                    // Ana m3u8 akışını doğrudan da ekleyelim
                                    callback(
                                        newExtractorLink(
                                            source = "JetFilm",
                                            name = "JetFilm ($langLabel - Master)",
                                            url = fullVideoLocation,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = fixedUrl
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    foundAny = true
                                }

                                if (!videoObj.dwlink.isNullOrBlank()) {
                                    val fullDw = fixUrl(videoObj.dwlink)
                                    callback(
                                        newExtractorLink(
                                            source = "JetFilm",
                                            name = "JetFilm ($langLabel - Direct)",
                                            url = fullDw,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = fixedUrl
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    foundAny = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("JetFilm", "bePlayer ayrıştırma hatası: ${e.message}")
                    }
                }

                // Standart CloudStream extractor'larına da ilet (Vidmoly, Streamtape vb.)
                loadExtractor(fixedUrl, pageUrl, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }

    private fun decryptBePlayer(passB64: String, payload: CryptoPayload): String? {
        try {
            val salt = hexToBytes(payload.s)
            val jsonIv = hexToBytes(payload.iv)
            val cleanCt = payload.ct.replace("\\/", "/").trim()
            val ct = try {
                Base64.decode(cleanCt, Base64.DEFAULT)
            } catch (_: Exception) {
                Base64.decode(cleanCt, Base64.NO_WRAP)
            }

            val passCandidates = listOf(
                passB64.toByteArray(Charsets.UTF_8),
                try { Base64.decode(passB64, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
            ).filter { it.isNotEmpty() }

            for (pass in passCandidates) {
                val derived = evpBytesToKeyFull(pass, salt, 32, 16)
                val derivedKey = derived.first
                val derivedIv = derived.second

                for (testIv in listOf(jsonIv, derivedIv)) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), IvParameterSpec(testIv))
                        val decryptedBytes = cipher.doFinal(ct)
                        val result = String(decryptedBytes, Charsets.UTF_8)
                        if (result.contains("video_location")) {
                            return result
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e("JetFilm", "AES decrypt error: ${e.message}")
        }
        return null
    }

    private fun evpBytesToKeyFull(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        var dtot = ByteArray(0)
        var d = ByteArray(0)
        while (dtot.size < (keyLen + ivLen)) {
            md.reset()
            if (d.isNotEmpty()) {
                md.update(d)
            }
            md.update(password)
            md.update(salt)
            d = md.digest()
            val newDtot = ByteArray(dtot.size + d.size)
            System.arraycopy(dtot, 0, newDtot, 0, dtot.size)
            System.arraycopy(d, 0, newDtot, dtot.size, d.size)
            dtot = newDtot
        }
        val key = ByteArray(keyLen)
        val iv = ByteArray(ivLen)
        System.arraycopy(dtot, 0, key, 0, keyLen)
        System.arraycopy(dtot, keyLen, iv, 0, ivLen)
        return Pair(key, iv)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
