package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingOnClick
import dev.brahmkshatriya.echo.common.settings.SettingCategory
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

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingCategory(
            title = "Profile",
            key = "profile",
            items = listOf(
                SettingTextInput(
                    key = "username",
                    title = "Your Name",
                    summary = "Name shown to others in rooms",
                    defaultValue = "User"
                )
            )
        ),
        SettingCategory(
            title = "Room",
            key = "room",
            items = listOf(
                SettingOnClick(
                    key = "create_room",
                    title = "Create Room",
                    summary = "Create a new listen together room"
                ) {
                    val username = setting.getString("username") ?: "User"
                    createRoom(username)
                },
                SettingTextInput(
                    key = "join_room_id",
                    title = "Room ID to Join",
                    summary = "Enter 6-character room ID",
                    defaultValue = ""
                ),
                SettingOnClick(
                    key = "join_room",
                    title = "Join Room",
                    summary = "Join an existing room"
                ) {
                    val roomId = setting.getString("join_room_id") ?: ""
                    if (roomId.isNotBlank()) {
                        setting.putString("current_room", roomId.uppercase())
                    }
                }
            )
        )
    )

    override fun setSettings(settings: Settings) { setting = settings }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
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
        setting.putString("current_room", roomId)
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
