package com.nobrainsoft.rangeanalyser.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nobrainsoft.rangeanalyser.core.geometry.AngularUnit
import com.nobrainsoft.rangeanalyser.core.geometry.LengthUnit
import com.nobrainsoft.rangeanalyser.core.geometry.UnitPreference
import com.nobrainsoft.rangeanalyser.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore(name = "range-analyser-settings")

/** How chatty the spoken shot calls are. */
enum class SpeechVerbosity {
    /** Silent. */
    OFF,

    /** "Nine." */
    SCORE_ONLY,

    /** "Shot seven, ten point four, two o'clock." */
    FULL,
}

data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val units: UnitPreference = UnitPreference(),
    val speech: SpeechVerbosity = SpeechVerbosity.FULL,
    /** Keeps the screen on during a live session. */
    val keepScreenOnWhileWatching: Boolean = true,
)

/**
 * App-wide preferences.
 *
 * Kept apart from profiles on purpose: a profile describes a gun and a load, and follows the
 * shooter's kit. These describe the phone and the person holding it.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.preferences.data.map { stored ->
        AppSettings(
            theme = stored[themeKey]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: AppTheme.SYSTEM,
            units = UnitPreference(
                length = stored[lengthUnitKey]
                    ?.let { runCatching { LengthUnit.valueOf(it) }.getOrNull() }
                    ?: LengthUnit.MILLIMETRES,
                angular = stored[angularUnitKey]
                    ?.let { runCatching { AngularUnit.valueOf(it) }.getOrNull() }
                    ?: AngularUnit.MOA,
                useYards = stored[useYardsKey] ?: false,
            ),
            speech = stored[speechKey]
                ?.let { runCatching { SpeechVerbosity.valueOf(it) }.getOrNull() }
                ?: SpeechVerbosity.FULL,
            keepScreenOnWhileWatching = stored[keepScreenOnKey] ?: true,
        )
    }

    suspend fun setTheme(theme: AppTheme) {
        context.preferences.edit { it[themeKey] = theme.name }
    }

    suspend fun setUnits(units: UnitPreference) {
        context.preferences.edit {
            it[lengthUnitKey] = units.length.name
            it[angularUnitKey] = units.angular.name
            it[useYardsKey] = units.useYards
        }
    }

    suspend fun setSpeech(verbosity: SpeechVerbosity) {
        context.preferences.edit { it[speechKey] = verbosity.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.preferences.edit { it[keepScreenOnKey] = enabled }
    }

    private companion object {
        val themeKey = stringPreferencesKey("theme")
        val lengthUnitKey = stringPreferencesKey("length_unit")
        val angularUnitKey = stringPreferencesKey("angular_unit")
        val useYardsKey = booleanPreferencesKey("use_yards")
        val speechKey = stringPreferencesKey("speech")
        val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    }
}
