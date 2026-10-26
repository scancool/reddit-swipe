package com.redditswipe

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redditswipe.data.AccountRepository
import com.redditswipe.data.AccountSync
import com.redditswipe.data.DEFAULT_NEWS_FEEDS
import com.redditswipe.data.SignedOutException
import com.redditswipe.data.DayStats
import com.redditswipe.data.LocalStore
import com.redditswipe.data.NEWS_FEEDS
import com.redditswipe.data.NewsRepository
import com.redditswipe.data.QUICK_READ_SUBREDDITS
import com.redditswipe.data.isQuickReadSubreddit
import com.redditswipe.data.RedditPost
import com.redditswipe.data.RedditRepository
import com.redditswipe.data.newsFeed
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Which cards the swipe screen deals: news, or Reddit stories of one length (Quick or Long) or any
 * length (Mixed, shown as "All").
 */
enum class ReadMode(val label: String) {
    Quick("Quick"),
    Mixed("All"),
    Long("Long"),
    News("News"),
    ;

    fun allows(post: RedditPost) = when (this) {
        Quick -> post.isQuickRead
        Mixed -> !post.isNews
        Long -> !post.isNews && !post.isQuickRead
        News -> post.isNews
    }

    fun allowsSubreddit(name: String) = when (this) {
        Quick -> isQuickReadSubreddit(name)
        Mixed -> true
        Long -> !isQuickReadSubreddit(name)
        News -> false
    }

    val includesNews: Boolean get() = this == News

    companion object {
        /** The story lengths offered under Stories. */
        val STORY_MODES = listOf(Mixed, Quick, Long)
    }
}

data class ReadingSettings(val textScale: Float = 1f, val serif: Boolean = false)

/** The optional account as the Account screen shows it. */
data class AccountState(
    /** Signed in as this address; null when signed out. */
    val email: String? = null,
    /** A code was emailed here and the app is waiting for it. */
    val pendingEmail: String? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long = 0L,
)

data class SwipeUiState(
    val deck: List<RedditPost> = emptyList(),
    val saved: List<RedditPost> = emptyList(),
    val subreddits: List<String> = emptyList(),
    val newsFeeds: List<String> = emptyList(),
    val showNsfw: Boolean = true,
    val reading: ReadingSettings = ReadingSettings(),
    val stats: Map<String, DayStats> = emptyMap(),
    val canUndo: Boolean = false,
    val isLoading: Boolean = false,
    // False until the saved deck has been read from disk, so the screen doesn't flash "caught up" at launch.
    val isRestored: Boolean = false,
    // The last fetch worked but every subreddit had nothing new.
    val isExhausted: Boolean = false,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val isCheckingSubreddit: Boolean = false,
    val subredditError: String? = null,
    val readMode: ReadMode = ReadMode.Mixed,
    // The story length the Stories tab goes back to; never News.
    val storyMode: ReadMode = ReadMode.Mixed,
    val showSwipeHint: Boolean = false,
    val account: AccountState = AccountState(),
) {
    /** The cards the swipe screen shows: the deck narrowed to the current [readMode]. */
    val visibleDeck: List<RedditPost> get() = deck.filter(readMode::allows)

    /** The subreddits that can supply cards in the current [readMode]. */
    val modeSubreddits: List<String> get() = subreddits.filter(readMode::allowsSubreddit)

    /** The news feeds that can supply cards in the current [readMode]. */
    val modeNewsFeeds: List<String> get() = if (readMode.includesNews) newsFeeds else emptyList()
}

@OptIn(FlowPreview::class)
class SwipeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RedditRepository()
    private val news = NewsRepository()
    private val store = LocalStore(app)
    private val account = AccountRepository(app)
    private val sync = AccountSync(account, ::syncSnapshot, ::syncFingerprint, ::applySyncSnapshot)

    private val _state = MutableStateFlow(
        SwipeUiState(
            subreddits = store.subreddits ?: DEFAULT_SUBREDDITS,
            newsFeeds = store.newsFeeds ?: DEFAULT_NEWS_FEEDS,
            showNsfw = store.showNsfw,
            reading = ReadingSettings(store.textScale, store.serif),
            readMode = ReadMode.entries.firstOrNull { it.name == store.readMode } ?: ReadMode.Mixed,
            storyMode = ReadMode.STORY_MODES.firstOrNull { it.name == (store.storyMode ?: store.readMode) } ?: ReadMode.Mixed,
            showSwipeHint = !store.swipeHintDone,
            account = AccountState(email = account.email, lastSyncedAt = account.syncedAt),
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

    // How many swiped-history keys the last upload carried, so going to the background uploads only if it grew.
    private var seenAtUpload = -1
    private var lastSyncAttempt = 0L

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
                    // Older versions could store the same story twice; one copy is kept.
                    deck = (loaded.first + state.deck).distinctBy { it.titleKey ?: it.id }.filter { state.allows(it) },
                    saved = loaded.second,
                    stats = loaded.third,
                    isRestored = true,
                )
            }
            restored = true
            refillIfLow()
            launch { runSync() }
            launch { syncWhenChanged() }
            persistChanges()
        }
    }

    fun swipeRight(post: RedditPost) = swipe(post, save = true)

    fun swipeLeft(post: RedditPost) = swipe(post, save = false)

    private fun swipe(post: RedditPost, save: Boolean) {
        val seconds = takeReadingSeconds()
        seen.addAll(post.seenKeys)
        history.addLast(post to save)
        if (history.size > MAX_UNDO) history.removeFirst()
        if (_state.value.showSwipeHint) store.swipeHintDone = true
        _state.update {
            it.copy(
                showSwipeHint = false,
                deck = it.deck.filterNot { card -> card.id == post.id },
                saved = if (save) it.saved + post else it.saved,
                canUndo = true,
                stats = it.stats.adjustToday(stories = 1, seconds = seconds),
            )
        }
        refillIfLow()
    }

    fun undo() {
        val (post, wasSaved) = history.removeLastOrNull() ?: return
        seen.removeAll(post.seenKeys.toSet())
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

    /** Puts a just-removed saved post back where it was (the Undo on the Saved screen). */
    fun restoreSaved(post: RedditPost, index: Int) {
        _state.update {
            if (post in it.saved) it
            else it.copy(saved = it.saved.toMutableList().apply { add(index.coerceIn(0, size), post) })
        }
    }

    fun setShowNsfw(show: Boolean) {
        store.showNsfw = show
        _state.update { state ->
            val updated = state.copy(showNsfw = show)
            updated.copy(deck = updated.deck.filter { updated.allows(it) })
        }
        if (show) loadMore() else refillIfLow()
    }

    fun setReadMode(mode: ReadMode) {
        store.readMode = mode.name
        if (mode != ReadMode.News) store.storyMode = mode.name
        _state.update {
            it.copy(
                readMode = mode,
                storyMode = if (mode == ReadMode.News) it.storyMode else mode,
                error = null,
                isExhausted = false,
            )
        }
        refillIfLow()
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

    /** Switches news feeds on or off: one chip on the News screen, or a whole category at once. */
    fun setNewsFeeds(names: Collection<String>, on: Boolean) {
        _state.update { state ->
            // Kept in NEWS_FEEDS order so the list reads the same however it was toggled.
            val feeds = NEWS_FEEDS.map { it.name }.filter { if (it in names) on else it in state.newsFeeds }
            val updated = state.copy(newsFeeds = feeds)
            updated.copy(deck = updated.deck.filter { updated.allows(it) })
        }
        store.newsFeeds = _state.value.newsFeeds
        if (on) loadMore() else refillIfLow()
    }

    fun onAppVisible() {
        if (visibleSince == null) visibleSince = SystemClock.elapsedRealtime()
        // Picks up what another phone changed while this one was away.
        if (restored && SystemClock.elapsedRealtime() - lastSyncAttempt > RESYNC_MS) viewModelScope.launch { runSync() }
        // The app can sit in the background for days; news that went stale meanwhile leaves the deck.
        if (restored && _state.value.deck.any { it.isStaleNews() }) {
            _state.update { state -> state.copy(deck = state.deck.filter { state.allows(it) }) }
            refillIfLow()
        }
    }

    fun onAppHidden() {
        visibleSince?.let { readingMs += SystemClock.elapsedRealtime() - it }
        visibleSince = null
        val snapshot = _state.value
        if (restored) viewModelScope.launch { persistAll(snapshot) }
        if (restored && seen.size != seenAtUpload) viewModelScope.launch { runSync(forceUpload = true) }
    }

    // --- Optional account: sign in with an emailed code, then sync (see AccountSync) ---

    fun requestSignInCode(input: String) {
        val email = input.trim().lowercase()
        accountAction {
            account.requestCode(email)
            _state.update { it.copy(account = it.account.copy(pendingEmail = email)) }
        }
    }

    fun verifySignInCode(code: String) {
        val email = _state.value.account.pendingEmail ?: return
        accountAction {
            account.verifyCode(email, code.trim())
            _state.update { it.copy(account = AccountState(email = account.email, isBusy = true)) }
            // The first sync merges this phone's likes into the account rather than replacing either side.
            runSync(firstTime = true, quiet = false)
        }
    }

    fun cancelSignIn() {
        _state.update { it.copy(account = AccountState()) }
    }

    fun signOut() {
        accountAction {
            account.signOut()
            _state.update { it.copy(account = AccountState()) }
        }
    }

    fun syncNow() {
        accountAction { runSync(forceUpload = true, quiet = false) }
    }

    /** Runs [block] with the Account screen showing it's busy, and shows any failure there. */
    private fun accountAction(block: suspend () -> Unit) {
        if (_state.value.account.isBusy) return
        _state.update { it.copy(account = it.account.copy(isBusy = true, error = null)) }
        viewModelScope.launch {
            val error = runCatching { block() }.exceptionOrNull()?.message
            _state.update { it.copy(account = it.account.copy(isBusy = false, error = error)) }
        }
    }

    /**
     * Syncs if signed in. Background syncs ([quiet]) fail silently, except that an ended session signs the
     * phone out; ones the user asked for throw, so the Account screen can say what went wrong.
     */
    private suspend fun runSync(firstTime: Boolean = false, forceUpload: Boolean = false, quiet: Boolean = true) {
        if (!account.isSignedIn) return
        lastSyncAttempt = SystemClock.elapsedRealtime()
        try {
            if (sync.sync(firstTime, forceUpload)) seenAtUpload = seen.size
            _state.update { it.copy(account = it.account.copy(lastSyncedAt = account.syncedAt)) }
        } catch (e: SignedOutException) {
            _state.update { it.copy(account = AccountState(error = e.message)) }
            if (!quiet) throw e
        } catch (e: Exception) {
            if (!quiet) throw e
        }
    }

    /** Uploads a few seconds after liked cards, sources or settings change on this phone. */
    private suspend fun syncWhenChanged() {
        _state.map { listOf(it.saved, it.subreddits, it.newsFeeds, it.reading, it.showNsfw, it.storyMode) }
            .distinctUntilChanged()
            .debounce(3_000)
            .collect { if (account.isSignedIn && account.syncedFingerprint != syncFingerprint()) runSync() }
    }

    private fun syncSnapshot(): JSONObject = with(_state.value) {
        JSONObject()
            .put("liked", JSONArray().apply { saved.forEach { put(it.toJson()) } })
            .put("subreddits", JSONArray(subreddits))
            .put("newsFeeds", JSONArray(newsFeeds))
            .put("seen", JSONArray(seen.toList().takeLast(SYNCED_SEEN)))
            .put(
                "settings",
                JSONObject()
                    .put("textScale", reading.textScale.toDouble())
                    .put("serif", reading.serif)
                    .put("showNsfw", showNsfw)
                    .put("storyMode", storyMode.name),
            )
    }

    /** The synced parts a person changes on purpose; the swiped history isn't one of them. */
    private fun syncFingerprint(): String = with(_state.value) {
        listOf(
            saved.joinToString(",") { it.id },
            subreddits.joinToString(","),
            newsFeeds.joinToString(","),
            reading.textScale,
            reading.serif,
            showNsfw,
            storyMode.name,
        ).joinToString("|")
    }

    private fun applySyncSnapshot(data: JSONObject, merge: Boolean) {
        data.optJSONArray("seen")?.let { keys -> repeat(keys.length()) { seen.add(keys.getString(it)) } }
        val liked = data.optJSONArray("liked")?.let { array ->
            List(array.length()) { RedditPost.fromJson(array.getJSONObject(it)) }
        }
        val remoteSubreddits = data.optJSONArray("subreddits")?.let { a -> List(a.length()) { a.getString(it) } }
        val remoteFeeds = data.optJSONArray("newsFeeds")?.let { a -> List(a.length()) { a.getString(it) } }
        val settings = data.optJSONObject("settings")
        _state.update { state ->
            val storyMode = settings?.optString("storyMode")
                ?.let { name -> ReadMode.STORY_MODES.firstOrNull { it.name == name } }
                ?: state.storyMode
            val updated = state.copy(
                // When both phones changed, liked cards from both are kept.
                saved = when {
                    liked == null -> state.saved
                    merge -> (state.saved + liked).distinctBy { it.id }
                    else -> liked
                },
                subreddits = remoteSubreddits ?: state.subreddits,
                newsFeeds = remoteFeeds ?: state.newsFeeds,
                showNsfw = settings?.optBoolean("showNsfw", state.showNsfw) ?: state.showNsfw,
                reading = ReadingSettings(
                    textScale = settings?.optDouble("textScale")?.takeUnless { it.isNaN() }?.toFloat()
                        ?: state.reading.textScale,
                    serif = settings?.optBoolean("serif", state.reading.serif) ?: state.reading.serif,
                ),
                storyMode = storyMode,
                readMode = if (state.readMode == ReadMode.News) ReadMode.News else storyMode,
            )
            // Cards the other phone already swiped leave this deck too.
            updated.copy(deck = updated.deck.filter { card -> updated.allows(card) && card.seenKeys.none(seen::contains) })
        }
        with(_state.value) {
            store.subreddits = subreddits
            store.newsFeeds = newsFeeds
            store.showNsfw = showNsfw
            store.textScale = reading.textScale
            store.serif = reading.serif
            store.storyMode = storyMode.name
            store.readMode = readMode.name
        }
        refillIfLow()
    }

    private fun takeReadingSeconds(): Int {
        val now = SystemClock.elapsedRealtime()
        val total = readingMs + (visibleSince?.let { now - it } ?: 0L)
        readingMs = 0L
        if (visibleSince != null) visibleSince = now
        return (total.coerceAtMost(MAX_READING_MS) / 1000).toInt()
    }

    private fun refillIfLow() {
        if (restored && _state.value.visibleDeck.size < 5) loadMore()
    }

    fun loadMore() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null, isExhausted = false) }
        viewModelScope.launch {
            val error = fetchRound(_state.value.readMode)
            _state.update {
                it.copy(
                    isLoading = false,
                    error = error.takeUnless { e -> e == NO_NEW_STORIES },
                    isExhausted = error == NO_NEW_STORIES,
                )
            }
        }
    }

    /** Fetches a few more pages from every subreddit (and the news feeds) so there is plenty to read offline. */
    fun downloadForOffline() {
        if (_state.value.isDownloading) return
        _state.update { it.copy(isDownloading = true) }
        viewModelScope.launch {
            fetchRound(ReadMode.News)
            repeat(OFFLINE_ROUNDS) { fetchRound(ReadMode.Mixed) }
            _state.update { it.copy(isDownloading = false) }
            persistAll(_state.value)
        }
    }

    /**
     * Adds one page from each subreddit and news feed that suits [mode] to the deck; returns an error
     * message if nothing came back.
     */
    private suspend fun fetchRound(mode: ReadMode): String? = fetchMutex.withLock {
        val subreddits = _state.value.subreddits.filter(mode::allowsSubreddit)
        val feeds = if (mode.includesNews) _state.value.newsFeeds.mapNotNull(::newsFeed) else emptyList()
        if (subreddits.isEmpty() && feeds.isEmpty()) return "Add some subreddits or news sources in the menu"
        val results = subreddits.map { sub ->
            viewModelScope.async { runCatching { repository.fetchTextPosts(sub, limit = 25) } }
        }.plus(feeds.map { feed ->
            viewModelScope.async { runCatching { news.fetchArticles(feed) } }
        }).awaitAll()
        val posts = results.flatMap { it.getOrDefault(emptyList()) }.shuffled()

        var added = 0
        _state.update { state ->
            val inDeck = state.deck.flatMapTo(HashSet()) { it.seenKeys }
            // Skips stories already read or dealt, and the same story arriving twice in this round.
            val fresh = posts
                .filter { post -> post.seenKeys.none { it in seen || it in inDeck } && state.allows(post) }
                .distinctBy { it.titleKey ?: it.id }
            added = fresh.size
            state.copy(deck = (state.deck + fresh).take(MAX_DECK))
        }
        if (added > 0) null
        else results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: NO_NEW_STORIES
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

    private fun SwipeUiState.allows(post: RedditPost): Boolean = when {
        post.isNews -> post.subreddit in newsFeeds && !post.isStaleNews()
        else -> (showNsfw || !post.isNsfw) && subreddits.any { it.equals(post.subreddit, ignoreCase = true) }
    }

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
        ) + QUICK_READ_SUBREDDITS

        private val SUBREDDIT_NAME = Regex("[A-Za-z0-9_]{2,21}")
        private const val MAX_UNDO = 30
        // Swiped-history keys sent with each upload; enough to keep a second phone from repeating recent cards.
        private const val SYNCED_SEEN = 3000
        private const val RESYNC_MS = 60_000L
        private const val MAX_DECK = 1000
        // Two keys per swiped story (ID and title), so about 5000 stories.
        private const val MAX_SEEN = 10000
        private const val OFFLINE_ROUNDS = 3
        private const val NO_NEW_STORIES = "No new stories found"
        // A card left open longer than this (phone set down) only counts this much.
        private const val MAX_READING_MS = 20 * 60 * 1000L
    }
}
