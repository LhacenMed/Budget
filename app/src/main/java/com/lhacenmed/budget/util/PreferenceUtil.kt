package com.lhacenmed.budget.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lhacenmed.budget.data.model.StatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val Context.appDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "theme_preferences")

object PreferenceUtil {

    // ── Defaults (single source of truth) ────────────────────────────────────
    val DEFAULT_DARK_THEME    = DarkThemePreference()
    val DEFAULT_DYNAMIC_COLOR = true
    val DEFAULT_COLOR_INDEX   = 0
    // Both sources visible by default
    val DEFAULT_SHOW_WHATSAPP          = true
    val DEFAULT_SHOW_WHATSAPP_BUSINESS = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>

    private val KEY_DARK_THEME              = intPreferencesKey("dark_theme_value")
    private val KEY_HIGH_CONTRAST           = booleanPreferencesKey("high_contrast")
    private val KEY_DYNAMIC_COLOR           = booleanPreferencesKey("dynamic_color")
    private val KEY_COLOR_INDEX             = intPreferencesKey("theme_color_index")
    private val KEY_SHOW_WHATSAPP           = booleanPreferencesKey("show_whatsapp")
    private val KEY_SHOW_WHATSAPP_BUSINESS  = booleanPreferencesKey("show_whatsapp_business")
    private val KEY_AUTH_SKIPPED            = booleanPreferencesKey("auth_skipped")

    private val _darkThemePreference   = MutableStateFlow(DEFAULT_DARK_THEME)
    val darkThemePreference: StateFlow<DarkThemePreference> = _darkThemePreference

    private val _isDynamicColorEnabled = MutableStateFlow(DEFAULT_DYNAMIC_COLOR)
    val isDynamicColorEnabled: StateFlow<Boolean> = _isDynamicColorEnabled

    private val _authSkipped = MutableStateFlow(false)
    val authSkipped: StateFlow<Boolean> = _authSkipped

    private val _themeColorIndex = MutableStateFlow(DEFAULT_COLOR_INDEX)
    val themeColorIndex: StateFlow<Int> = _themeColorIndex

    private val _visibleStatusSources = MutableStateFlow(
        setOf(StatusSource.WHATSAPP, StatusSource.WHATSAPP_BUSINESS)
    )
    val visibleStatusSources: StateFlow<Set<StatusSource>> = _visibleStatusSources

    fun init(context: Context) {
        dataStore = context.applicationContext.appDataStore
        scope.launch {
            dataStore.data.collect { prefs ->
                _darkThemePreference.value = DarkThemePreference(
                    darkThemeValue            = prefs[KEY_DARK_THEME]    ?: DEFAULT_DARK_THEME.darkThemeValue,
                    isHighContrastModeEnabled = prefs[KEY_HIGH_CONTRAST] ?: DEFAULT_DARK_THEME.isHighContrastModeEnabled,
                )
                _isDynamicColorEnabled.value = prefs[KEY_DYNAMIC_COLOR] ?: DEFAULT_DYNAMIC_COLOR
                _themeColorIndex.value       = prefs[KEY_COLOR_INDEX]   ?: DEFAULT_COLOR_INDEX
                _authSkipped.value           = prefs[KEY_AUTH_SKIPPED]  ?: false

                val showWa  = prefs[KEY_SHOW_WHATSAPP]          ?: DEFAULT_SHOW_WHATSAPP
                val showBiz = prefs[KEY_SHOW_WHATSAPP_BUSINESS]  ?: DEFAULT_SHOW_WHATSAPP_BUSINESS
                // Enforce at least one source — if both were somehow persisted as false, reset to both
                _visibleStatusSources.value = when {
                    showWa && showBiz -> setOf(StatusSource.WHATSAPP, StatusSource.WHATSAPP_BUSINESS)
                    showWa            -> setOf(StatusSource.WHATSAPP)
                    showBiz           -> setOf(StatusSource.WHATSAPP_BUSINESS)
                    else              -> setOf(StatusSource.WHATSAPP, StatusSource.WHATSAPP_BUSINESS)
                }
            }
        }
    }

    fun modifyDarkThemePreference(
        darkThemeValue: Int = _darkThemePreference.value.darkThemeValue,
        isHighContrastModeEnabled: Boolean = _darkThemePreference.value.isHighContrastModeEnabled,
    ) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_DARK_THEME]    = darkThemeValue
                prefs[KEY_HIGH_CONTRAST] = isHighContrastModeEnabled
            }
        }
    }

    fun switchDynamicColor(enabled: Boolean = !_isDynamicColorEnabled.value) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_DYNAMIC_COLOR] = enabled
            }
        }
    }

    fun modifyThemeColor(index: Int) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_COLOR_INDEX] = index
            }
        }
    }

    fun setVisibleStatusSources(sources: Set<StatusSource>) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_SHOW_WHATSAPP]          = StatusSource.WHATSAPP          in sources
                prefs[KEY_SHOW_WHATSAPP_BUSINESS] = StatusSource.WHATSAPP_BUSINESS in sources
            }
        }
    }

    fun setAuthSkipped(skipped: Boolean) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_AUTH_SKIPPED] = skipped
            }
        }
    }
}
