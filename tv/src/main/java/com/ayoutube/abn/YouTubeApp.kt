package com.ayoutube.abn

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

class YouTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response =
                executeWithRedirects(request.url(), request, 0)

            private fun executeWithRedirects(urlStr: String, req: Request, depth: Int): Response {
                if (depth > 5) throw Exception("Too many redirects")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                try {
                    // Fix #1: Force TLS 1.2 — Android 9 can fail TLS handshakes
                    // with certain YouTube CDN hosts without explicit TLS version
                    if (conn is HttpsURLConnection) {
                        try {
                            val ctx = SSLContext.getInstance("TLSv1.2")
                            ctx.init(null, null, null)
                            conn.sslSocketFactory = ctx.socketFactory
                        } catch (_: Exception) {}
                    }
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    conn.requestMethod = req.httpMethod()
                    // Fix #4: disable auto-follow so we can handle HTTP→HTTPS ourselves
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                    req.headers().forEach { (key, value) ->
                        if (key != null && value != null && value.isNotEmpty()) {
                            try { conn.setRequestProperty(key, value[0]) } catch (_: Exception) {}
                        }
                    }
                    val data = req.dataToSend()
                    if (req.httpMethod() == "POST" && data != null) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(data) }
                    }
                    val code = conn.responseCode
                    // Fix #4: follow redirects manually including HTTP→HTTPS
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        if (!location.isNullOrEmpty()) {
                            conn.disconnect()
                            val next = if (location.startsWith("http")) location
                                       else URL(url, location).toString()
                            return executeWithRedirects(next, req, depth + 1)
                        }
                    }
                    val msg = conn.responseMessage ?: ""
                    val stream: InputStream = try {
                        if (code in 200..299) conn.inputStream
                        else conn.errorStream ?: conn.inputStream
                    } catch (e: Exception) { conn.errorStream ?: throw e }
                    val body = stream.bufferedReader().use { it.readText() }
                    // Fix #7 in downloader: filter null-key headers — Android 9 headerFields
                    // can include null keys that crash NewPipe's Response parser
                    val headers = conn.headerFields.filterKeys { it != null }
                    return Response(code, msg, headers, body, urlStr)
                } finally {
                    conn.disconnect() // Fix: always release connection on Android 9
                }
            }
        })
    }
}
