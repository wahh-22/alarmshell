package dev.wahh.alarmshell.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** A pre-built cloud call. HTTPS only, and never a redirect, cookie jar or cached response. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
    /** Raw JSON body for API-key based calls; mutually exclusive with [form]. */
    val json: String? = null,
    val accept: String = "application/json",
) {
    init {
        require(method == "GET" || method == "POST" || method == "PUT") { "unsupported method" }
        require(url.startsWith("https://")) { "cloud calls must be HTTPS" }
        require(url.length <= 2048)
    }

    fun bodyBytes(): ByteArray? =
        json?.toByteArray(Charsets.UTF_8)
            ?: form
                ?.entries
                ?.joinToString("&") { (key, value) ->
                    URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
                }?.toByteArray(Charsets.UTF_8)
}

data class HttpResponse(
    val status: Int,
    val bytes: ByteArray,
) {
    /** UTF-8 view for JSON/text endpoints. Do not call on binary payloads such as the captcha PNG. */
    val body: String get() = String(bytes, Charsets.UTF_8)

    companion object {
        fun text(
            status: Int,
            body: String,
        ): HttpResponse = HttpResponse(status, body.toByteArray(Charsets.UTF_8))
    }
}

interface Transport {
    fun execute(request: HttpRequest): HttpResponse
}

class HttpsTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : Transport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = open(URL(request.url))
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Accept", request.accept)
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val body = request.bodyBytes()
            if (body != null) {
                connection.doOutput = true
                val contentType = if (request.json != null) "application/json; charset=UTF-8" else "application/x-www-form-urlencoded; charset=UTF-8"
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                body.fill(0)
            }
            val status = connection.responseCode
            val deadline = System.nanoTime() + 40_000_000_000L
            val stream: InputStream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: return HttpResponse(status, ByteArray(0))
                }
            return HttpResponse(status, stream.use { it.readBounded(262144, deadline) })
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBounded(
        limit: Int,
        deadline: Long,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
            val count = read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= limit) { "Response too large" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
}
