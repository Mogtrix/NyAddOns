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

    /** Downloads a JSON file to [target]. Leaves an existing copy alone unless the download is valid JSON. */
    fun json(url: String, target: File): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            JsonParser.parseString(response.body())
            target.parentFile.mkdirs()
            target.writeText(response.body())
            true
        } else {
            NyAddOns.logger.warn("Could not download $url: HTTP ${response.statusCode()}")
            false
        }
    } catch (e: Exception) {
        NyAddOns.logger.warn("Could not download $url: $e")
        false
    }
}
