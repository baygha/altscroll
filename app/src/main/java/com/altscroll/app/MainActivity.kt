package com.altscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.altscroll.app.data.SupabaseProvider
import com.altscroll.app.data.repo.AuthRepository
import com.altscroll.app.ui.auth.AuthScreen
import com.altscroll.app.ui.common.EmptyState
import com.altscroll.app.ui.common.LoadingBox
import com.altscroll.app.ui.nav.AltScrollNav
import com.altscroll.app.ui.theme.AltScrollTheme
import com.altscroll.app.ui.theme.LocalThemeState
import com.altscroll.app.ui.theme.ThemeState
import com.altscroll.app.ui.theme.ThemeStore
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the one piece of state the whole app branches on: is someone signed in.
 * Supabase restores a persisted session asynchronously, so the first emission is
 * [SessionStatus.Initializing] and the UI shows a spinner rather than flashing
 * the sign-in screen at a returning user.
 */
class SessionGateViewModel(
    repo: AuthRepository = AuthRepository(),
) : ViewModel() {
    val status: StateFlow<SessionStatus> = repo.sessionStatus.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionStatus.Initializing,
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AltScrollRoot() }
    }
}

@Composable
private fun AltScrollRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = follow the system; set only once the user taps the toggle.
    val override by remember { ThemeStore.darkOverride(context) }
        .collectAsState(initial = null)
    val systemDark = isSystemInDarkTheme()
    val isDark = override ?: systemDark

    val themeState = ThemeState(
        isDark = isDark,
        toggle = {
            scope.launch {
                // Writing the opposite of what is on screen means the first tap
                // always visibly flips, whichever way the system was leaning.
                ThemeStore.setDark(context, !isDark)
            }
        },
    )

    CompositionLocalProvider(LocalThemeState provides themeState) {
        AltScrollTheme(darkTheme = isDark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SignedInOrAuth()
            }
        }
    }
}

@Composable
private fun SignedInOrAuth() {
    if (!SupabaseProvider.isConfigured) {
        EmptyState(
            title = "Supabase is not configured",
            detail = "Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties, " +
                "then rebuild. See docs/SETUP.md.",
        )
        return
    }

    val gate: SessionGateViewModel = viewModel()
    val status by gate.status.collectAsStateWithLifecycle()

    when (status) {
        is SessionStatus.Initializing -> LoadingBox()
        is SessionStatus.Authenticated -> AltScrollNav()
        else -> AuthScreen()
    }
}
