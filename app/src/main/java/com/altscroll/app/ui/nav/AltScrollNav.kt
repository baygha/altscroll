package com.altscroll.app.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.altscroll.app.ui.chat.ChatScreen
import com.altscroll.app.ui.chat.ConversationsScreen
import com.altscroll.app.ui.feed.FeedScreen
import com.altscroll.app.ui.post.NewPostScreen

object Routes {
    const val FEED = "feed"
    const val NEW_POST = "new_post"
    const val CHATS = "chats"
    const val CHAT_DETAIL = "chat/{conversationId}"

    fun chatDetail(conversationId: String) = "chat/$conversationId"
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab(Routes.FEED, "Feed", Icons.Filled.Layers),
    Tab(Routes.NEW_POST, "Post", Icons.Filled.AddCircleOutline),
    Tab(Routes.CHATS, "Chats", Icons.AutoMirrored.Filled.Chat),
)

@Composable
fun AltScrollNav(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The chat thread is full-screen; the bar would just eat vertical space.
    val showBottomBar = currentRoute != Routes.CHAT_DETAIL

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.FEED,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.FEED) { FeedScreen() }

            composable(Routes.NEW_POST) {
                NewPostScreen(onPublished = { navController.switchTab(Routes.FEED) })
            }

            composable(Routes.CHATS) {
                ConversationsScreen(
                    onOpenConversation = { id ->
                        navController.navigate(Routes.chatDetail(id))
                    }
                )
            }

            composable(Routes.CHAT_DETAIL) { entry ->
                val id = entry.arguments?.getString("conversationId")
                if (id == null) {
                    navController.popBackStack()
                } else {
                    ChatScreen(
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** Tab switching that keeps one entry per tab instead of stacking duplicates. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
