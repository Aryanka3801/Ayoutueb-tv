package com.ayoutube.abn

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class YouTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val url = URL(request.url())
                val connection = url.openConnection() as HttpURLConnection

                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.requestMethod = request.httpMethod()

                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.5")

                request.headers().forEach { (key, value) ->
                    if (key != null && value != null && value.isNotEmpty()) {
                        connection.setRequestProperty(key, value[0])
                    }
                }

                val dataToSend = request.dataToSend()
                if (request.httpMethod() == "POST" && dataToSend != null) {
                    connection.doOutput = true
                    connection.outputStream.use { os ->
                        os.write(dataToSend)
                    }
                }

                val responseCode = connection.responseCode
                val responseMessage = connection.responseMessage ?: ""

                val inputStream: InputStream = try {
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream ?: connection.inputStream
                    }
                } catch (e: Exception) {
                    connection.errorStream ?: throw e
                }

                val responseBody = inputStream.bufferedReader().use { it.readText() }

                // Filter out null keys from headerFields — Android 9 includes null-key entries
                // that cause NullPointerException in NewPipe's Response parsing
                val filteredHeaders = connection.headerFields
                    .filterKeys { it != null }

                return Response(responseCode, responseMessage, filteredHeaders, responseBody, request.url())
            }
        })
    }
}
