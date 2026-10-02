package dev.nytrix.nyaddons.core

import com.google.gson.JsonParser
import dev.nytrix.nyaddons.NyAddOns
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object Downloads {

    private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Brings the copy of a JSON file at [target] up to date, at most once a day.
     *
     * Returns true only when the file's contents changed, so callers can skip reading it again.
     * A failed or invalid download leaves the existing copy alone.
     */
    fun refresh(url: String, target: File): Boolean {
        if (target.exists() && System.currentTimeMillis() - target.lastModified() < MAX_AGE_MILLIS) return false
        return try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                NyAddOns.logger.warn("Could not download $url: HTTP ${response.statusCode()}")
                return false
            }
            val body = response.body()
            if (target.exists() && target.readText() == body) {
                target.setLastModified(System.currentTimeMillis())
                return false
            }
            JsonParser.parseReader(body.reader())
            target.parentFile.mkdirs()
            target.writeText(body)
            true
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not download $url: $e")
            false
        }
    }
}
