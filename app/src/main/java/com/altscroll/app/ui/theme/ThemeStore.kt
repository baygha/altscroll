package com.altscroll.app.ui.theme

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("altscroll_settings")
private val DARK_KEY = booleanPreferencesKey("dark_theme")

/**
 * Remembers the user's theme choice across launches.
 *
 * `null` means "follow the system", which is the default — the app only pins a
 * value once the user actually taps the toggle.
 */
object ThemeStore {

    fun darkOverride(context: Context): Flow<Boolean?> =
        context.settingsStore.data.map { prefs -> prefs[DARK_KEY] }

    suspend fun setDark(context: Context, dark: Boolean?) {
        context.settingsStore.edit { prefs ->
            if (dark == null) prefs.remove(DARK_KEY) else prefs[DARK_KEY] = dark
        }
    }
}

@Immutable
data class ThemeState(
    val isDark: Boolean,
    val toggle: () -> Unit,
)

val LocalThemeState = compositionLocalOf {
    ThemeState(isDark = true, toggle = {})
}
