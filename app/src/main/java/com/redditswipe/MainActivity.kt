package com.redditswipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redditswipe.ui.SavedScreen
import com.redditswipe.ui.SwipeScreen
import com.redditswipe.ui.theme.RedditSwipeTheme
import com.redditswipe.update.UpdateDialog
import com.redditswipe.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RedditSwipeTheme {
                val vm: SwipeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }

                val updates: UpdateViewModel = viewModel()
                val updateState by updates.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.onResume() }
                UpdateDialog(updateState, onUpdate = updates::update, onDismiss = updates::dismiss)

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.Style, contentDescription = null) },
                                label = { Text("Swipe") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = {
                                    BadgedBox(badge = {
                                        if (state.saved.isNotEmpty()) Badge { Text("${state.saved.size}") }
                                    }) {
                                        Icon(Icons.Filled.Bookmarks, contentDescription = null)
                                    }
                                },
                                label = { Text("Saved") },
                            )
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> SwipeScreen(
                            state = state,
                            onSwipeLeft = vm::swipeLeft,
                            onSwipeRight = vm::swipeRight,
                            onRetry = vm::loadMore,
                            modifier = Modifier.padding(padding),
                        )
                        else -> SavedScreen(
                            saved = state.saved,
                            onRemove = vm::removeSaved,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}
