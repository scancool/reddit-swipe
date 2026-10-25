package com.redditswipe.data

import org.json.JSONObject

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
) {
    val url: String get() = "https://www.reddit.com$permalink"

    /** Estimated reading time at about 230 words per minute. */
    val readMinutes: Int
        get() {
            val words = "$title $body".split(WHITESPACE).count { it.isNotEmpty() }
            return maxOf(1, (words + 115) / 230)
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
        )
    }
}
