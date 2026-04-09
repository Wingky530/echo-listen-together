package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class ListenTogetherExtension : ExtensionClient, HomeFeedClient {

    private lateinit var setting: Settings
    private val httpClient = OkHttpClient()
    private val firebaseUrl = "https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app"

    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) { setting = settings }

    override fun loadHomeFeed(): Feed<Shelf> {
        return PagedData.Single<Shelf> {
            val rooms = getRooms()
            val categories = rooms.map { (roomId, host) ->
                Shelf.Category(
                    id = roomId,
                    title = "Room $roomId",
                    subtitle = "Host: $host"
                )
            }
            listOf(
                Shelf.Lists.Categories(
                    id = "rooms",
                    title = "Active Rooms",
                    list = categories
                )
            )
        }.toFeed()
    }

    private suspend fun getRooms(): List<Pair<String, String>> {
        val request = Request.Builder()
            .url("$firebaseUrl/rooms.json")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string() ?: return emptyList()
        if (body == "null") return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val regex = Regex(""""([A-Z0-9]{6})"\s*:\s*\{[^}]*"host"\s*:\s*"([^"]+)"""")
        regex.findAll(body).forEach { match ->
            result.add(match.groupValues[1] to match.groupValues[2])
        }
        return result
    }

    suspend fun createRoom(hostName: String): String {
        val roomId = UUID.randomUUID().toString().take(6).uppercase()
        val data = """{"host":"$hostName","track":"","position":0,"isPlaying":false,"updatedAt":${System.currentTimeMillis()}}"""
        val body = data.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .put(body)
            .build()
        httpClient.newCall(request).await()
        return roomId
    }

    suspend fun updateState(roomId: String, trackId: String, position: Long, isPlaying: Boolean) {
        val data = """{"track":"$trackId","position":$position,"isPlaying":$isPlaying,"updatedAt":${System.currentTimeMillis()}}"""
        val body = data.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .patch(body)
            .build()
        httpClient.newCall(request).await()
    }

    suspend fun getState(roomId: String): String? {
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string() ?: return null
        return if (body == "null") null else body
    }
}
