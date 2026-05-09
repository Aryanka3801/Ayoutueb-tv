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
        // Initialize NewPipe with a more robust downloader that supports POST requests
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val url = URL(request.url())
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = request.httpMethod()
                
                // Set default headers
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.5")

                request.headers().forEach { (key, value) ->
                    connection.setRequestProperty(key, value[0])
                }
                
                val dataToSend = request.dataToSend()
                if (request.httpMethod() == "POST" && dataToSend != null) {
                    connection.doOutput = true
                    connection.outputStream.use { os ->
                        os.write(dataToSend)
                    }
                }
                
                val responseCode = connection.responseCode
                val responseMessage = connection.responseMessage
                val inputStream: InputStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseBody = inputStream.bufferedReader().use { it.readText() }

                return Response(responseCode, responseMessage, connection.headerFields, responseBody, request.url())
            }
        })
    }
}
