package com.redditswipe.data

import android.content.Context
import androidx.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DayStats(val stories: Int = 0, val seconds: Int = 0)

/**
 * Everything the app keeps on the phone: settings in SharedPreferences, and the card deck
 * (which doubles as the offline cache), saved posts, seen post IDs and reading stats as JSON files.
 * File access blocks, so call the load/save functions off the main thread.
 */
class LocalStore(context: Context) {

    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var showNsfw: Boolean
        get() = prefs.getBoolean("show_nsfw", true)
        set(value) = prefs.edit().putBoolean("show_nsfw", value).apply()

    /** Null until the user changes the list, so the app's defaults apply. */
    var subreddits: List<String>?
        get() = prefs.getString("subreddits", null)?.split(",")?.filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("subreddits", value?.joinToString(",")).apply()

    /** Names of the switched-on [NEWS_FEEDS]; null until the user changes them, so the defaults apply. */
    var newsFeeds: List<String>?
        get() = prefs.getString("news_feeds", null)?.split(",")?.filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("news_feeds", value?.joinToString(",")).apply()

    /** Card text size, set in Settings or by pinching a card. */
    var textScale: Float
        // Before 0.12, pinching kept its own extra zoom (body_zoom); it's folded in here and dropped on the next save.
        get() = prefs.getFloat("text_scale", 1f) * prefs.getFloat("body_zoom", 1f)
        set(value) = prefs.edit().putFloat("text_scale", value).remove("body_zoom").apply()

    /** Name of the [com.redditswipe.ReadMode] picked on the swipe screen. */
    var readMode: String?
        get() = prefs.getString("read_mode", null)
        set(value) = prefs.edit().putString("read_mode", value).apply()

    /** Which story length (Quick, Mixed or Long) the Stories tab returns to after reading news. */
    var storyMode: String?
        get() = prefs.getString("story_mode", null)
        set(value) = prefs.edit().putString("story_mode", value).apply()

    /** True once the user has swiped a card, after which the "← Skip · Like →" hint stays hidden. */
    var swipeHintDone: Boolean
        get() = prefs.getBoolean("swipe_hint_done", false)
        set(value) = prefs.edit().putBoolean("swipe_hint_done", value).apply()

    var serif: Boolean
        get() = prefs.getBoolean("serif", false)
        set(value) = prefs.edit().putBoolean("serif", value).apply()

    fun loadDeck() = loadPosts("deck.json")
    fun saveDeck(posts: List<RedditPost>) = savePosts("deck.json", posts)

    fun loadSaved() = loadPosts("saved.json")
    fun saveSaved(posts: List<RedditPost>) = savePosts("saved.json", posts)

    fun loadSeen(): List<String> = read("seen.json")?.let { text ->
        val array = JSONArray(text)
        List(array.length()) { array.getString(it) }
    } ?: emptyList()

    fun saveSeen(ids: List<String>) = write("seen.json", JSONArray(ids).toString())

    fun loadStats(): Map<String, DayStats> = read("stats.json")?.let { text ->
        val json = JSONObject(text)
        json.keys().asSequence().associateWith { day ->
            val entry = json.getJSONObject(day)
            DayStats(entry.optInt("stories"), entry.optInt("seconds"))
        }
    } ?: emptyMap()

    fun saveStats(stats: Map<String, DayStats>) {
        val json = JSONObject()
        stats.forEach { (day, s) ->
            json.put(day, JSONObject().put("stories", s.stories).put("seconds", s.seconds))
        }
        write("stats.json", json.toString())
    }

    private fun loadPosts(name: String): List<RedditPost> = read(name)?.let { text ->
        val array = JSONArray(text)
        List(array.length()) { RedditPost.fromJson(array.getJSONObject(it)) }
    } ?: emptyList()

    private fun savePosts(name: String, posts: List<RedditPost>) {
        val array = JSONArray()
        posts.forEach { array.put(it.toJson()) }
        write(name, array.toString())
    }

    // A corrupt or missing file just means starting fresh for that piece.
    private fun read(name: String): String? = runCatching {
        val file = AtomicFile(File(dir, name))
        if (!file.baseFile.exists()) null else file.readFully().decodeToString()
    }.getOrNull()

    private fun write(name: String, text: String) {
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(text.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }
}
