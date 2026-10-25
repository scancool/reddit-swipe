package com.redditswipe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches posts from Reddit's public JSON listings and keeps only text (self) posts.
 */
class RedditRepository {

    // Pagination cursor per subreddit, so each fetch returns new posts.
    private val afterTokens = mutableMapOf<String, String?>()

    suspend fun fetchTextPosts(subreddit: String, limit: Int = 50): List<RedditPost> =
        withContext(Dispatchers.IO) {
            val after = afterTokens[subreddit]
            val query = buildString {
                append("limit=").append(limit).append("&raw_json=1")
                if (after != null) append("&after=").append(URLEncoder.encode(after, "UTF-8"))
            }
            val url = URL("https://www.reddit.com/r/$subreddit/hot.json?$query")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                // Reddit rejects requests without a descriptive User-Agent.
                setRequestProperty("User-Agent", "android:com.redditswipe:v0.1 (personal use)")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw RedditException("r/$subreddit returned HTTP ${connection.responseCode}")
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val data = JSONObject(json).getJSONObject("data")
                afterTokens[subreddit] = data.optString("after").takeIf { it.isNotEmpty() && it != "null" }
                parseTextPosts(data)
            } finally {
                connection.disconnect()
            }
        }

    private fun parseTextPosts(data: JSONObject): List<RedditPost> {
        val children = data.getJSONArray("children")
        return (0 until children.length()).mapNotNull { i ->
            val post = children.getJSONObject(i).getJSONObject("data")
            val isText = post.optBoolean("is_self") &&
                !post.optBoolean("is_video") &&
                !post.optBoolean("over_18") &&
                !post.optBoolean("stickied")
            if (!isText) return@mapNotNull null
            RedditPost(
                id = post.getString("id"),
                subreddit = post.getString("subreddit"),
                title = post.getString("title"),
                body = post.optString("selftext"),
                author = post.optString("author"),
                score = post.optInt("score"),
                numComments = post.optInt("num_comments"),
                permalink = post.getString("permalink"),
            )
        }
    }
}

class RedditException(message: String) : Exception(message)
