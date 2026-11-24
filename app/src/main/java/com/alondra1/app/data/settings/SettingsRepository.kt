package com.alondra1.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alondra1.app.domain.TaskSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }
enum class TimeFormat(val label: String) { SYSTEM("Follow system"), H12("12-hour"), H24("24-hour") }
enum class Tab(val route: String) { TODO("todo"), NOTES("notes"), CALENDAR("calendar") }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val firstDayOfWeek: DayOfWeek? = null,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    val defaultListId: String? = null,
    val lastTab: Tab = Tab.TODO,
    val onboardingComplete: Boolean = false,
    val taskSort: TaskSort = TaskSort.DUE_DATE,
    val privateNotifications: Boolean = true,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val firstDay = stringPreferencesKey("first_day_of_week")
        val timeFormat = stringPreferencesKey("time_format")
        val defaultList = stringPreferencesKey("default_list_id")
        val lastTab = stringPreferencesKey("last_tab")
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val sort = stringPreferencesKey("task_sort")
        val privateNotifications = booleanPreferencesKey("private_notifications")
    }

    val settings: Flow<Settings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            Settings(
                theme = enumOr(p[Keys.theme], ThemeMode.DARK),
                firstDayOfWeek = p[Keys.firstDay]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() },
                timeFormat = enumOr(p[Keys.timeFormat], TimeFormat.SYSTEM),
                defaultListId = p[Keys.defaultList],
                lastTab = enumOr(p[Keys.lastTab], Tab.TODO),
                onboardingComplete = p[Keys.onboarding] ?: false,
                taskSort = enumOr(p[Keys.sort], TaskSort.DUE_DATE),
                privateNotifications = p[Keys.privateNotifications] ?: true,
            )
        }

    suspend fun setTheme(value: ThemeMode) = store.edit { it[Keys.theme] = value.name }
    suspend fun setFirstDayOfWeek(value: DayOfWeek?) = store.edit { if (value == null) it.remove(Keys.firstDay) else it[Keys.firstDay] = value.name }
    suspend fun setTimeFormat(value: TimeFormat) = store.edit { it[Keys.timeFormat] = value.name }
    suspend fun setDefaultList(id: String?) = store.edit { if (id == null) it.remove(Keys.defaultList) else it[Keys.defaultList] = id }
    suspend fun setLastTab(tab: Tab) = store.edit { it[Keys.lastTab] = tab.name }
    suspend fun setOnboardingComplete() = store.edit { it[Keys.onboarding] = true }
    suspend fun setTaskSort(sort: TaskSort) = store.edit { it[Keys.sort] = sort.name }
    suspend fun setPrivateNotifications(value: Boolean) = store.edit { it[Keys.privateNotifications] = value }
    suspend fun clear() = store.edit { it.clear() }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, fallback: E): E =
        value?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
}
