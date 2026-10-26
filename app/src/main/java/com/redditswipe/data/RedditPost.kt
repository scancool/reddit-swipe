package com.redditswipe.data

import org.json.JSONObject

/**
 * One card: a Reddit text post, or a news article when [isNews] is set. For news, [subreddit] holds the
 * feed's name (see [NEWS_FEEDS]) and [permalink] the article's full URL.
 */
data class RedditPost(
    val id: String,
    val subreddit: String,
    val title: String,
    val body: String,
    val author: String,
    val score: Int,
    val numComments: Int,
    val permalink: String,
    val isNsfw: Boolean,
    val isNews: Boolean = false,
    /** When the article was published, in epoch milliseconds; 0 for Reddit posts and unknown dates. */
    val publishedAt: Long = 0L,
) {
    val url: String get() = if (isNews) permalink else "https://www.reddit.com$permalink"

    /** Where the card came from, as shown above it: "r/tifu" or "BBC News". */
    val sourceLabel: String get() = if (isNews) subreddit else "r/$subreddit"

    /** From a subreddit of short posts (see [QUICK_READ_SUBREDDITS]) rather than a story subreddit. */
    val isQuickRead: Boolean by lazy { !isNews && isQuickReadSubreddit(subreddit) }

    /** Estimated reading time at about 230 words per minute; computed once, since cards redraw often. */
    val readMinutes: Int by lazy {
        val words = "$title $body".split(WHITESPACE).count { it.isNotEmpty() }
        maxOf(1, (words + 115) / 230)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("subreddit", subreddit)
        .put("title", title)
        .put("body", body)
        .put("author", author)
        .put("score", score)
        .put("numComments", numComments)
        .put("permalink", permalink)
        .put("isNsfw", isNsfw)
        .put("isNews", isNews)
        .put("publishedAt", publishedAt)

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun fromJson(json: JSONObject) = RedditPost(
            id = json.getString("id"),
            subreddit = json.getString("subreddit"),
            title = json.getString("title"),
            body = json.optString("body"),
            author = json.optString("author"),
            score = json.optInt("score"),
            numComments = json.optInt("numComments"),
            permalink = json.getString("permalink"),
            isNsfw = json.optBoolean("isNsfw"),
            isNews = json.optBoolean("isNews"),
            publishedAt = json.optLong("publishedAt"),
        )
    }
}
