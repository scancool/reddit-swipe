package com.redditswipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.redditswipe.data.RedditPost
import com.redditswipe.data.RedditRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SwipeUiState(
    val deck: List<RedditPost> = emptyList(),
    val saved: List<RedditPost> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SwipeViewModel(
    private val repository: RedditRepository = RedditRepository(),
) : ViewModel() {

    val subreddits = listOf(
        "AskReddit",
        "explainlikeimfive",
        "todayilearned",
        "NoStupidQuestions",
        "LifeProTips",
        "Showerthoughts",
        "TrueOffMyChest",
        "tifu",
    )

    private val _state = MutableStateFlow(SwipeUiState())
    val state: StateFlow<SwipeUiState> = _state.asStateFlow()

    private val seenIds = mutableSetOf<String>()

    init {
        loadMore()
    }

    fun swipeRight(post: RedditPost) {
        _state.update { it.copy(deck = it.deck - post, saved = it.saved + post) }
        refillIfLow()
    }

    fun swipeLeft(post: RedditPost) {
        _state.update { it.copy(deck = it.deck - post) }
        refillIfLow()
    }

    fun removeSaved(post: RedditPost) {
        _state.update { it.copy(saved = it.saved - post) }
    }

    private fun refillIfLow() {
        if (_state.value.deck.size < 5) loadMore()
    }

    fun loadMore() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val results = subreddits.map { sub ->
                async { runCatching { repository.fetchTextPosts(sub, limit = 25) } }
            }.awaitAll()

            val posts = results.flatMap { it.getOrDefault(emptyList()) }
                .filter { seenIds.add(it.id) }
                .shuffled()
            val error = if (posts.isEmpty()) {
                results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                    ?: "No new text posts found"
            } else {
                null
            }
            _state.update { it.copy(deck = it.deck + posts, isLoading = false, error = error) }
        }
    }
}
