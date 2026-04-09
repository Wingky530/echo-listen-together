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
                    val existingRoom = setting.getString("current_room")
                    if (existingRoom != null && existingRoom.isNotBlank()) {
                        deleteRoom(existingRoom)
                    }
                    val roomId = createRoom(username)
                    setting.putString("current_room", roomId)
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
                },
                SettingOnClick(
                    key = "update_now_playing",
                    title = "Update Now Playing",
                    summary = "Update what you're currently playing (paste track info)"
                ) {
                    val roomId = setting.getString("current_room") ?: return@SettingOnClick
                    val trackInfo = setting.getString("now_playing_input") ?: ""
                    if (trackInfo.isNotBlank()) {
                        updateNowPlaying(roomId, trackInfo)
                    }
                },
                SettingTextInput(
                    key = "now_playing_input",
                    title = "Now Playing Info",
                    summary = "Enter track name to share with room",
                    defaultValue = ""
                ),
                SettingOnClick(
                    key = "leave_room",
                    title = "Leave/Delete Room",
                    summary = "Leave current room or delete if you are host"
                ) {
                    val roomId = setting.getString("current_room") ?: return@SettingOnClick
                    val username = setting.getString("username") ?: "User"
                    val state = getState(roomId)
                    if (state != null && state.contains("\"host\":\"$username\"")) {
                        deleteRoom(roomId)
                    }
                    setting.putString("current_room", "")
                }
            )
        )
    )

    override fun setSettings(settings: Settings) { setting = settings }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        return PagedData.Single<Shelf> {
            val shelves = mutableListOf<Shelf>()

            // Show current room with now playing info
            val currentRoom = setting.getString("current_room")
            if (!currentRoom.isNullOrBlank()) {
                val state = getState(currentRoom)
                val nowPlaying = extractField(state, "nowPlaying") ?: "Nothing playing"
                val host = extractField(state, "host") ?: "Unknown"
                shelves.add(
                    Shelf.Lists.Categories(
                        id = "current_room",
                        title = "Your Room: $currentRoom",
                        list = listOf(
                            Shelf.Category(
                                id = "now_playing",
                                title = "🎵 $nowPlaying",
                                subtitle = "Host: $host • Search this song to sync!"
                            )
                        )
                    )
                )
            }

            // Show all active rooms
            val rooms = getRooms()
            if (rooms.isNotEmpty()) {
                val categories = rooms.map { (roomId, host, nowPlaying) ->
                    Shelf.Category(
                        id = roomId,
                        title = "Room $roomId",
                        subtitle = "Host: $host • 🎵 $nowPlaying"
                    )
                }
                shelves.add(
                    Shelf.Lists.Categories(
                        id = "rooms",
                        title = "Active Rooms",
                        list = categories
                    )
                )
            }

            shelves
        }.toFeed()
    }

    private fun extractField(json: String?, field: String): String? {
        if (json == null) return null
        val regex = Regex(""""$field"\s*:\s*"([^"]+)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private suspend fun getRooms(): List<Triple<String, String, String>> {
        val request = Request.Builder()
            .url("$firebaseUrl/rooms.json")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string() ?: return emptyList()
        if (body == "null") return emptyList()
        val result = mutableListOf<Triple<String, String, String>>()
        val roomRegex = Regex(""""([A-Z0-9]{6})"\s*:\s*\{([^}]+)\}""")
        roomRegex.findAll(body).forEach { match ->
            val roomId = match.groupValues[1]
            val roomData = match.groupValues[2]
            val host = Regex(""""host"\s*:\s*"([^"]+)"""").find(roomData)?.groupValues?.get(1) ?: "Unknown"
            val nowPlaying = Regex(""""nowPlaying"\s*:\s*"([^"]+)"""").find(roomData)?.groupValues?.get(1) ?: "Nothing playing"
            result.add(Triple(roomId, host, nowPlaying))
        }
        return result
    }

    private suspend fun createRoom(hostName: String): String {
        val roomId = UUID.randomUUID().toString().take(6).uppercase()
        val data = """{"host":"$hostName","nowPlaying":"","position":0,"isPlaying":false,"updatedAt":${System.currentTimeMillis()}}"""
        val body = data.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .put(body)
            .build()
        httpClient.newCall(request).await()
        return roomId
    }

    private suspend fun deleteRoom(roomId: String) {
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .delete()
            .build()
        httpClient.newCall(request).await()
    }

    private suspend fun updateNowPlaying(roomId: String, trackInfo: String) {
        val data = """{"nowPlaying":"$trackInfo","updatedAt":${System.currentTimeMillis()}}"""
        val body = data.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .patch(body)
            .build()
        httpClient.newCall(request).await()
    }

    private suspend fun getState(roomId: String): String? {
        val request = Request.Builder()
            .url("$firebaseUrl/rooms/$roomId.json")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string() ?: return null
        return if (body == "null") null else body
    }
}
