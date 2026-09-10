package com.nobrainsoft.rangeanalyser.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.stats.AmmoRanking
import com.nobrainsoft.rangeanalyser.core.stats.LoadPerformance
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class LibraryState(
    val firearms: List<Firearm> = emptyList(),
    val ammo: List<Ammo> = emptyList(),
    val targets: List<TargetSpec> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    /** How each load has actually shot, so the list is useful rather than just a catalogue. */
    val loadPerformance: Map<String, LoadPerformance> = emptyMap(),
)

/**
 * Backs every library screen and the profile editor.
 *
 * One view model rather than four, because the screens constantly need each other's data - a
 * firearm's calibre filters the ammunition list, and a profile references all three.
 */
class LibraryViewModel(private val repository: RangeRepository) : ViewModel() {

    val state: StateFlow<LibraryState> = combine(
        repository.observeFirearms(),
        repository.observeAmmo(),
        repository.observeTargets(),
        repository.observeProfiles(),
        repository.observeAnalysedSessions().map { AmmoRanking.rank(it) },
    ) { firearms, ammo, targets, profiles, ranking ->
        LibraryState(
            firearms = firearms,
            ammo = ammo,
            targets = targets,
            profiles = profiles,
            loadPerformance = ranking.entries.mapNotNull { entry ->
                entry.ammoId?.let { it to entry }
            }.toMap(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    // The item currently open in an editor. Loading is an explicit call rather than a side effect
    // of reading the flow, so a recomposition cannot restart it and throw away the user's edits.
    private val editingFirearm = MutableStateFlow<Firearm?>(null)
    private val editingAmmo = MutableStateFlow<Ammo?>(null)
    private val editingProfile = MutableStateFlow<Profile?>(null)

    val firearm: StateFlow<Firearm?> = editingFirearm
    val ammo: StateFlow<Ammo?> = editingAmmo
    val profile: StateFlow<Profile?> = editingProfile

    fun loadFirearm(id: String?) {
        viewModelScope.launch {
            editingFirearm.value = id?.let { repository.findFirearm(it) } ?: blankFirearm()
        }
    }

    fun loadAmmo(id: String?) {
        viewModelScope.launch {
            editingAmmo.value = id?.let { repository.findAmmo(it) } ?: blankAmmo()
        }
    }

    fun loadProfile(id: String?) {
        viewModelScope.launch {
            editingProfile.value = id?.let { repository.findProfile(it) } ?: blankProfile()
        }
    }

    fun updateFirearm(firearm: Firearm) {
        editingFirearm.value = firearm
    }

    fun updateAmmo(ammo: Ammo) {
        editingAmmo.value = ammo
    }

    fun updateProfile(profile: Profile) {
        editingProfile.value = profile
    }

    fun saveFirearm(firearm: Firearm) = viewModelScope.launch { repository.saveFirearm(firearm) }

    fun saveAmmo(ammo: Ammo) = viewModelScope.launch { repository.saveAmmo(ammo) }

    fun saveProfile(profile: Profile) = viewModelScope.launch { repository.saveProfile(profile) }

    fun deleteFirearm(firearm: Firearm) = viewModelScope.launch { repository.deleteFirearm(firearm) }

    fun deleteAmmo(ammo: Ammo) = viewModelScope.launch { repository.deleteAmmo(ammo) }

    fun deleteProfile(profile: Profile) = viewModelScope.launch { repository.deleteProfile(profile) }

    fun saveCustomTarget(spec: TargetSpec) = viewModelScope.launch {
        repository.saveCustomTarget(spec, System.currentTimeMillis())
    }

    fun deleteCustomTarget(spec: TargetSpec) = viewModelScope.launch {
        repository.deleteCustomTarget(spec)
    }

    private fun blankFirearm() = Firearm(
        id = UUID.randomUUID().toString(),
        name = "",
        type = com.nobrainsoft.rangeanalyser.core.model.FirearmType.CENTREFIRE_RIFLE,
        caliberId = com.nobrainsoft.rangeanalyser.core.model.Calibers.R_308.id,
    )

    private fun blankAmmo() = Ammo(
        id = UUID.randomUUID().toString(),
        brand = "",
        caliberId = com.nobrainsoft.rangeanalyser.core.model.Calibers.R_308.id,
    )

    private fun blankProfile() = Profile(
        id = UUID.randomUUID().toString(),
        name = "",
        firearmId = "",
        targetSpecId = com.nobrainsoft.rangeanalyser.core.target.TargetLibrary.BLANK_A4.id,
        distanceM = 100.0,
    )
}
