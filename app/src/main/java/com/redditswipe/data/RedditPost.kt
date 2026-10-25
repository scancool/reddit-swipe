package com.redditswipe.data

data class RedditPost(
    val id: String,
    val subreddit: String,
    val title: String,
    val body: String,
    val author: String,
    val score: Int,
    val numComments: Int,
    val permalink: String,
) {
    val url: String get() = "https://www.reddit.com$permalink"
}
