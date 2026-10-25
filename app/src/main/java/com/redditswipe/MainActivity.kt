package com.redditswipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redditswipe.ui.SavedScreen
import com.redditswipe.ui.SwipeScreen
import com.redditswipe.ui.theme.RedditSwipeTheme
import com.redditswipe.update.UpdateDialog
import com.redditswipe.update.UpdateViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RedditSwipeTheme {
                val vm: SwipeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                val updates: UpdateViewModel = viewModel()
                val updateState by updates.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.onResume() }
                UpdateDialog(updateState, onUpdate = updates::update, onDismiss = updates::dismiss)

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // Edge swipes would fight with card swipes, so the menu opens only from its button.
                    gesturesEnabled = drawerState.isOpen,
                    drawerContent = {
                        ModalDrawerSheet {
                            Text(
                                "Reddit Swipe",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
                            )
                            NavigationDrawerItem(
                                label = { Text("Swipe") },
                                icon = { Icon(Icons.Filled.Style, contentDescription = null) },
                                selected = tab == 0,
                                onClick = { tab = 0; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                label = { Text("Saved") },
                                icon = { Icon(Icons.Filled.Bookmarks, contentDescription = null) },
                                badge = { if (state.saved.isNotEmpty()) Text("${state.saved.size}") },
                                selected = tab == 1,
                                onClick = { tab = 1; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 28.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Show NSFW stories", Modifier.weight(1f))
                                Switch(checked = state.showNsfw, onCheckedChange = vm::setShowNsfw)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Version ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(28.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    },
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(if (tab == 0) "Swipe" else "Saved") },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                                    }
                                },
                            )
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
}
