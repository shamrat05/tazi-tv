package com.ftptv.app.fetcher

import com.ftptv.app.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ChannelFetcher(private val server: String, private val port: Int) {

    suspend fun fetch(): Result<List<Channel>> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://$server:$port/")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val channels = parseChannels(html)
            Result.success(channels)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseChannels(html: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val regex = Regex(
            """data-category="([^"]*)"\s*data-channel="([^"]*)".*?src="([^"]*)"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in regex.findAll(html)) {
            val category = match.groupValues[1]
            val streamUrl = match.groupValues[2]
            val imgSrc = match.groupValues[3]

            val name = imgSrc.substringAfterLast("/").substringBeforeLast(".")
                .replace("_", " ").replace("-", " ").trim()
                .replace(Regex("\\s+"), " ")

            channels.add(Channel(
                name = name.ifEmpty { streamUrl.substringAfterLast("/").substringBeforeLast("/") },
                category = category,
                streamUrl = streamUrl,
                thumbnailUrl = "http://$server:$port/$imgSrc"
            ))
        }
        return channels.distinctBy { it.streamUrl }
    }
}
