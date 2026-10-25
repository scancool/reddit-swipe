package com.redditswipe.data

import com.redditswipe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches subreddit listings through the relay Worker (see /relay), which calls Reddit's
 * official API, and keeps only text (self) posts: story-length ones, or any length from the
 * quick-read subreddits in [QUICK_READ_SUBREDDITS].
 */
class RedditRepository {

    // Pagination cursor per subreddit, so each fetch returns new posts.
    private val afterTokens = mutableMapOf<String, String?>()

    suspend fun fetchTextPosts(subreddit: String, limit: Int = 50): List<RedditPost> {
        val (posts, after) = fetchListing(subreddit, limit, afterTokens[subreddit])
        afterTokens[subreddit] = after
        return posts
    }

    /** Returns the stories on the first page of [subreddit], without moving its cursor. */
    suspend fun checkSubreddit(subreddit: String): List<RedditPost> =
        fetchListing(subreddit, 50, after = null).first

    private suspend fun fetchListing(
        subreddit: String,
        limit: Int,
        after: String?,
    ): Pair<List<RedditPost>, String?> = withContext(Dispatchers.IO) {
        val query = buildString {
            append("limit=").append(limit)
            if (after != null) append("&after=").append(URLEncoder.encode(after, "UTF-8"))
        }
        val url = URL("${BuildConfig.RELAY_URL}/r/$subreddit/hot?$query")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw RedditException("r/$subreddit returned HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(json).getJSONObject("data")
            val next = data.optString("after").takeIf { it.isNotEmpty() && it != "null" }
            parseTextPosts(data, isQuickReadSubreddit(subreddit)) to next
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTextPosts(data: JSONObject, quickRead: Boolean): List<RedditPost> {
        val children = data.getJSONArray("children")
        return (0 until children.length()).mapNotNull { i ->
            val post = children.getJSONObject(i).getJSONObject("data")
            // Removed and deleted posts keep their title but replace the body with a marker.
            val body = post.optString("selftext").takeUnless { it == "[removed]" || it == "[deleted]" }.orEmpty()
            val isText = post.optBoolean("is_self") &&
                !post.optBoolean("is_video") &&
                !post.optBoolean("stickied") &&
                post.isNull("removed_by_category")
            val longEnough = quickRead || body.length >= MIN_BODY_LENGTH
            if (!isText || !longEnough) return@mapNotNull null
            RedditPost(
                id = post.getString("id"),
                subreddit = post.getString("subreddit"),
                title = post.getString("title"),
                body = body,
                author = post.optString("author"),
                score = post.optInt("score"),
                numComments = post.optInt("num_comments"),
                permalink = post.getString("permalink"),
                isNsfw = post.optBoolean("over_18"),
            )
        }
    }
}

// Shorter posts are usually questions whose answers live in the comments.
private const val MIN_BODY_LENGTH = 300

/** Subreddits whose whole point is a short post (often just the title), so any length is kept. */
val QUICK_READ_SUBREDDITS = listOf(
    "Showerthoughts",
    "LifeProTips",
    "YouShouldKnow",
    "Jokes",
    "DadJokes",
)

fun isQuickReadSubreddit(name: String) = QUICK_READ_SUBREDDITS.any { it.equals(name, ignoreCase = true) }

class RedditException(message: String) : Exception(message)
