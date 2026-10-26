package com.redditswipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redditswipe.ui.NewsSourcesScreen
import com.redditswipe.ui.SavedScreen
import com.redditswipe.ui.SettingsScreen
import com.redditswipe.ui.StatsScreen
import com.redditswipe.ui.SubredditsScreen
import com.redditswipe.ui.SwipeScreen
import com.redditswipe.ui.theme.RedditSwipeTheme
import com.redditswipe.update.UpdateDialog
import com.redditswipe.update.UpdateViewModel
import kotlinx.coroutines.launch

enum class Screen(val title: String, val icon: ImageVector) {
    Swipe("Swipe", Icons.Filled.Style),
    Saved("Saved", Icons.Filled.Bookmarks),
    News("News sources", Icons.Filled.Newspaper),
    Subreddits("Subreddits", Icons.Filled.Forum),
    Stats("Stats", Icons.Filled.BarChart),
    Settings("Settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RedditSwipeTheme {
                val vm: SwipeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf(Screen.Swipe) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                val updates: UpdateViewModel = viewModel()
                val updateState by updates.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    updates.onResume()
                    vm.onAppVisible()
                }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onAppHidden() }
                UpdateDialog(updateState, onUpdate = updates::update, onDismiss = updates::dismiss)

                // Back returns to Swipe from other screens; the later handler (open menu) wins.
                BackHandler(enabled = screen != Screen.Swipe) { screen = Screen.Swipe }
                BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

                // The drawer only slides in from the start edge, so it's laid out right-to-left to put the menu
                // on the right, within thumb reach; its sheet and the screens switch back to left-to-right.
                val ltr = LocalLayoutDirection.current
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        // Edge swipes would fight with card swipes, so the menu opens only from its button.
                        gesturesEnabled = drawerState.isOpen,
                        drawerContent = {
                            CompositionLocalProvider(LocalLayoutDirection provides ltr) {
                                ModalDrawerSheet {
                                    Text(
                                        "Reddit Swipe",
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
                                    )
                                    Screen.entries.forEach { item ->
                                        NavigationDrawerItem(
                                            label = { Text(item.title) },
                                            icon = { Icon(item.icon, contentDescription = null) },
                                            badge = {
                                                if (item == Screen.Saved && state.saved.isNotEmpty()) Text("${state.saved.size}")
                                            },
                                            selected = screen == item,
                                            onClick = { screen = item; scope.launch { drawerState.close() } },
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        )
                                    }
                                }
                            }
                        },
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides ltr) {
                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text(screen.title) },
                                        actions = {
                                            if (screen == Screen.Swipe) {
                                                IconButton(onClick = vm::undo, enabled = state.canUndo) {
                                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last swipe")
                                                }
                                            }
                                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                                            }
                                        },
                                    )
                                },
                            ) { padding ->
                                val content = Modifier.padding(padding)
                                when (screen) {
                                    Screen.Swipe -> SwipeScreen(
                                        state = state,
                                        onSwipeLeft = vm::swipeLeft,
                                        onSwipeRight = vm::swipeRight,
                                        onRetry = vm::loadMore,
                                        onOpenSubreddits = { screen = Screen.Subreddits },
                                        onOpenNews = { screen = Screen.News },
                                        onBodyZoom = vm::setBodyZoom,
                                        onReadMode = vm::setReadMode,
                                        modifier = content,
                                    )
                                    Screen.Saved -> SavedScreen(
                                        saved = state.saved,
                                        onRemove = vm::removeSaved,
                                        modifier = content,
                                    )
                                    Screen.News -> NewsSourcesScreen(
                                        newsFeeds = state.newsFeeds,
                                        onSetFeeds = vm::setNewsFeeds,
                                        modifier = content,
                                    )
                                    Screen.Subreddits -> SubredditsScreen(
                                        subreddits = state.subreddits,
                                        isChecking = state.isCheckingSubreddit,
                                        error = state.subredditError,
                                        onAdd = vm::addSubreddit,
                                        onRemove = vm::removeSubreddit,
                                        onRestoreDefaults = vm::restoreDefaultSubreddits,
                                        modifier = content,
                                    )
                                    Screen.Stats -> StatsScreen(stats = state.stats, modifier = content)
                                    Screen.Settings -> SettingsScreen(
                                        reading = state.reading,
                                        showNsfw = state.showNsfw,
                                        storiesReady = state.deck.size,
                                        isDownloading = state.isDownloading,
                                        onTextScale = vm::setTextScale,
                                        onSerif = vm::setSerif,
                                        onBodyZoom = vm::setBodyZoom,
                                        onShowNsfw = vm::setShowNsfw,
                                        onDownload = vm::downloadForOffline,
                                        modifier = content,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
