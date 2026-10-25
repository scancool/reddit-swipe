package com.redditswipe

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redditswipe.data.DayStats
import com.redditswipe.data.LocalStore
import com.redditswipe.data.RedditPost
import com.redditswipe.data.RedditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class ReadingSettings(val textScale: Float = 1f, val serif: Boolean = false)

data class SwipeUiState(
    val deck: List<RedditPost> = emptyList(),
    val saved: List<RedditPost> = emptyList(),
    val subreddits: List<String> = emptyList(),
    val showNsfw: Boolean = true,
    val reading: ReadingSettings = ReadingSettings(),
    val stats: Map<String, DayStats> = emptyMap(),
    val canUndo: Boolean = false,
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val isCheckingSubreddit: Boolean = false,
    val subredditError: String? = null,
)

@OptIn(FlowPreview::class)
class SwipeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RedditRepository()
    private val store = LocalStore(app)

    private val _state = MutableStateFlow(
        SwipeUiState(
            subreddits = store.subreddits ?: DEFAULT_SUBREDDITS,
            showNsfw = store.showNsfw,
            reading = ReadingSettings(store.textScale, store.serif),
        ),
    )
    val state: StateFlow<SwipeUiState> = _state.asStateFlow()

    // IDs of posts already swiped, kept across restarts so stories don't repeat.
    private val seen = LinkedHashSet<String>()

    // Most recent swipes, newest last: the post and whether it was saved.
    private val history = ArrayDeque<Pair<RedditPost, Boolean>>()

    // One fetch round at a time, so pagination cursors don't race.
    private val fetchMutex = Mutex()

    private var restored = false

    // Reading time for the current card, counted only while the app is on screen.
    private var visibleSince: Long? = null
    private var readingMs = 0L

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                seen.addAll(store.loadSeen())
                Triple(store.loadDeck(), store.loadSaved(), store.loadStats())
            }
            _state.update { state ->
                state.copy(
                    deck = (loaded.first + state.deck).distinctBy { it.id }.filter { state.allows(it) },
                    saved = loaded.second,
                    stats = loaded.third,
                )
            }
            restored = true
            refillIfLow()
            persistChanges()
        }
    }

    fun swipeRight(post: RedditPost) = swipe(post, save = true)

    fun swipeLeft(post: RedditPost) = swipe(post, save = false)

    private fun swipe(post: RedditPost, save: Boolean) {
        val seconds = takeReadingSeconds()
        seen.add(post.id)
        history.addLast(post to save)
        if (history.size > MAX_UNDO) history.removeFirst()
        _state.update {
            it.copy(
                deck = it.deck - post,
                saved = if (save) it.saved + post else it.saved,
                canUndo = true,
                stats = it.stats.adjustToday(stories = 1, seconds = seconds),
            )
        }
        refillIfLow()
    }

    fun undo() {
        val (post, wasSaved) = history.removeLastOrNull() ?: return
        seen.remove(post.id)
        _state.update {
            it.copy(
                deck = listOf(post) + it.deck,
                saved = if (wasSaved) it.saved - post else it.saved,
                canUndo = history.isNotEmpty(),
                stats = it.stats.adjustToday(stories = -1, seconds = 0),
            )
        }
    }

    fun removeSaved(post: RedditPost) {
        _state.update { it.copy(saved = it.saved - post) }
    }

    fun setShowNsfw(show: Boolean) {
        store.showNsfw = show
        _state.update { state ->
            val updated = state.copy(showNsfw = show)
            updated.copy(deck = updated.deck.filter { updated.allows(it) })
        }
        if (show) loadMore() else refillIfLow()
    }

    fun setTextScale(scale: Float) {
        store.textScale = scale
        _state.update { it.copy(reading = it.reading.copy(textScale = scale)) }
    }

    fun setSerif(serif: Boolean) {
        store.serif = serif
        _state.update { it.copy(reading = it.reading.copy(serif = serif)) }
    }

    fun addSubreddit(input: String) {
        val name = input.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (!SUBREDDIT_NAME.matches(name)) {
            _state.update { it.copy(subredditError = "That isn't a valid subreddit name") }
            return
        }
        if (_state.value.subreddits.any { it.equals(name, ignoreCase = true) }) {
            _state.update { it.copy(subredditError = "r/$name is already in your list") }
            return
        }
        _state.update { it.copy(isCheckingSubreddit = true, subredditError = null) }
        viewModelScope.launch {
            val stories = runCatching { repository.checkSubreddit(name) }.getOrNull()
            if (stories.isNullOrEmpty()) {
                _state.update {
                    it.copy(isCheckingSubreddit = false, subredditError = "Couldn't find text stories in r/$name")
                }
                return@launch
            }
            // Use Reddit's capitalization of the name.
            val canonical = stories.first().subreddit
            _state.update { it.copy(subreddits = it.subreddits + canonical, isCheckingSubreddit = false) }
            store.subreddits = _state.value.subreddits
            loadMore()
        }
    }

    fun removeSubreddit(name: String) {
        _state.update { state ->
            val updated = state.copy(subreddits = state.subreddits - name, subredditError = null)
            updated.copy(deck = updated.deck.filter { updated.allows(it) })
        }
        store.subreddits = _state.value.subreddits
        refillIfLow()
    }

    fun restoreDefaultSubreddits() {
        store.subreddits = null
        _state.update { it.copy(subreddits = DEFAULT_SUBREDDITS, subredditError = null) }
        loadMore()
    }

    fun onAppVisible() {
        if (visibleSince == null) visibleSince = SystemClock.elapsedRealtime()
    }

    fun onAppHidden() {
        visibleSince?.let { readingMs += SystemClock.elapsedRealtime() - it }
        visibleSince = null
        val snapshot = _state.value
        if (restored) viewModelScope.launch { persistAll(snapshot) }
    }

    private fun takeReadingSeconds(): Int {
        val now = SystemClock.elapsedRealtime()
        val total = readingMs + (visibleSince?.let { now - it } ?: 0L)
        readingMs = 0L
        if (visibleSince != null) visibleSince = now
        return (total.coerceAtMost(MAX_READING_MS) / 1000).toInt()
    }

    private fun refillIfLow() {
        if (restored && _state.value.deck.size < 5) loadMore()
    }

    fun loadMore() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val error = fetchRound()
            _state.update { it.copy(isLoading = false, error = error) }
        }
    }

    /** Fetches a few more pages from every subreddit so there is plenty to read offline. */
    fun downloadForOffline() {
        if (_state.value.isDownloading) return
        _state.update { it.copy(isDownloading = true) }
        viewModelScope.launch {
            repeat(OFFLINE_ROUNDS) { fetchRound() }
            _state.update { it.copy(isDownloading = false) }
            persistAll(_state.value)
        }
    }

    /** Adds one page from each subreddit to the deck; returns an error message if nothing came back. */
    private suspend fun fetchRound(): String? = fetchMutex.withLock {
        val subreddits = _state.value.subreddits
        if (subreddits.isEmpty()) return "Add some subreddits in the menu"
        val results = subreddits.map { sub ->
            viewModelScope.async { runCatching { repository.fetchTextPosts(sub, limit = 25) } }
        }.awaitAll()
        val posts = results.flatMap { it.getOrDefault(emptyList()) }.shuffled()

        var added = 0
        _state.update { state ->
            val inDeck = state.deck.mapTo(HashSet()) { it.id }
            val fresh = posts.filter { it.id !in seen && it.id !in inDeck && state.allows(it) }
            added = fresh.size
            state.copy(deck = (state.deck + fresh).take(MAX_DECK))
        }
        if (added > 0) null
        else results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "No new stories found"
    }

    /** Saves to disk shortly after things change; the deck is large, so it waits longer. */
    private suspend fun persistChanges() {
        viewModelScope.launch {
            _state.map { it.deck }.distinctUntilChanged().debounce(5_000).collect { deck ->
                withContext(Dispatchers.IO) { store.saveDeck(deck) }
            }
        }
        _state.map { Triple(it.saved, it.stats, seen.size) }.distinctUntilChanged().debounce(1_000).collect {
            val seenSnapshot = seen.toList().takeLast(MAX_SEEN)
            withContext(Dispatchers.IO) {
                store.saveSaved(it.first)
                store.saveStats(it.second)
                store.saveSeen(seenSnapshot)
            }
        }
    }

    private suspend fun persistAll(state: SwipeUiState) {
        val seenSnapshot = seen.toList().takeLast(MAX_SEEN)
        withContext(Dispatchers.IO) {
            store.saveDeck(state.deck)
            store.saveSaved(state.saved)
            store.saveStats(state.stats)
            store.saveSeen(seenSnapshot)
        }
    }

    private fun SwipeUiState.allows(post: RedditPost): Boolean =
        (showNsfw || !post.isNsfw) && subreddits.any { it.equals(post.subreddit, ignoreCase = true) }

    private fun Map<String, DayStats>.adjustToday(stories: Int, seconds: Int): Map<String, DayStats> {
        val today = LocalDate.now().toString()
        val current = this[today] ?: DayStats()
        return this + (today to DayStats(
            stories = (current.stories + stories).coerceAtLeast(0),
            seconds = current.seconds + seconds,
        ))
    }

    companion object {
        // Subreddits where the whole story is in the post itself.
        val DEFAULT_SUBREDDITS = listOf(
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
            "gonewildstories",
            "sexstories",
            "eroticliterature",
        )

        private val SUBREDDIT_NAME = Regex("[A-Za-z0-9_]{2,21}")
        private const val MAX_UNDO = 30
        private const val MAX_DECK = 1000
        private const val MAX_SEEN = 5000
        private const val OFFLINE_ROUNDS = 3
        // A card left open longer than this (phone set down) only counts this much.
        private const val MAX_READING_MS = 20 * 60 * 1000L
    }
}
