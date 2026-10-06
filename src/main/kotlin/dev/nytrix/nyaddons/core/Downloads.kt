package dev.nytrix.nyaddons.core

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.nytrix.nyaddons.NyAddOns
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

object Downloads {

    private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    // One client for every download and the Greenhouse solver: each client keeps a selector thread of its own alive.
    val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    /**
     * Brings the copy of a JSON file at [target] up to date, at most once a day. Runs on the calling thread, so call it from a
     * background thread.
     *
     * Returns true only when the file's contents changed, so callers can skip reading it again.
     * A failed or invalid download leaves the existing copy alone. The download goes straight to a file and is only
     * checked token by token, so even the 3 MB recipe list never sits in memory as text or as a JSON tree.
     */
    fun refresh(url: String, target: File): Boolean {
        if (target.exists() && System.currentTimeMillis() - target.lastModified() < MAX_AGE_MILLIS) return false
        val temp = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile.mkdirs()
            temp.delete()
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp.toPath()))
            if (response.statusCode() != 200) {
                NyAddOns.logger.warn("Could not download $url: HTTP ${response.statusCode()}")
                return false
            }
            if (target.exists() && Files.mismatch(temp.toPath(), target.toPath()) == -1L) {
                target.setLastModified(System.currentTimeMillis())
                return false
            }
            requireJson(temp)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not download $url: $e")
            false
        } finally {
            temp.delete()
        }
    }

    private fun requireJson(file: File) {
        file.bufferedReader().use { source ->
            val reader = JsonReader(source)
            reader.strictness = Strictness.LENIENT
            reader.skipValue()
            check(reader.peek() == JsonToken.END_DOCUMENT) { "more than one JSON value" }
        }
    }
}
