package com.redditswipe

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
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
    val showNsfw: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SwipeViewModel(app: Application) : AndroidViewModel(app) {

    // Subreddits where the whole story is in the post itself.
    private val storySubreddits = listOf(
        "TrueOffMyChest",
        "offmychest",
        "confessions",
        "tifu",
        "AmItheAsshole",
        "AITAH",
        "pettyrevenge",
        "ProRevenge",
        "MaliciousCompliance",
        "entitledparents",
        "BestofRedditorUpdates",
        "nosleep",
        "TalesFromRetail",
        "TalesFromTechSupport",
        "relationship_advice",
    )

    // Only loaded while "Show NSFW stories" is on.
    private val nsfwSubreddits = listOf(
        "gonewildstories",
        "sexstories",
        "eroticliterature",
    )

    private val repository = RedditRepository()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SwipeUiState(showNsfw = prefs.getBoolean(KEY_NSFW, true)))
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

    fun setShowNsfw(show: Boolean) {
        prefs.edit().putBoolean(KEY_NSFW, show).apply()
        _state.update { state ->
            state.copy(
                showNsfw = show,
                deck = if (show) state.deck else state.deck.filterNot { it.isNsfw },
            )
        }
        if (show) loadMore() else refillIfLow()
    }

    private fun refillIfLow() {
        if (_state.value.deck.size < 5) loadMore()
    }

    fun loadMore() {
        if (_state.value.isLoading) return
        val showNsfw = _state.value.showNsfw
        val subreddits = if (showNsfw) storySubreddits + nsfwSubreddits else storySubreddits
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val results = subreddits.map { sub ->
                async { runCatching { repository.fetchTextPosts(sub, limit = 25) } }
            }.awaitAll()

            val posts = results.flatMap { it.getOrDefault(emptyList()) }
                .filter { showNsfw || !it.isNsfw }
                .filter { seenIds.add(it.id) }
                .shuffled()
            val error = if (posts.isEmpty()) {
                results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                    ?: "No new stories found"
            } else {
                null
            }
            _state.update { state ->
                // The setting may have been switched off while this load was running.
                val fresh = if (state.showNsfw) posts else posts.filterNot { it.isNsfw }
                state.copy(deck = state.deck + fresh, isLoading = false, error = error)
            }
        }
    }

    private companion object {
        const val KEY_NSFW = "show_nsfw"
    }
}
