package com.nobrainsoft.rangeanalyser.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class ProfileSummary(
    val profile: Profile,
    val firearmName: String?,
    val ammoName: String?,
    val targetName: String?,
)

data class RecentSession(
    val id: String,
    val name: String,
    val subtitle: String,
    val headline: String,
    val headlineLabel: String,
)

data class HomeState(
    val profiles: List<ProfileSummary> = emptyList(),
    val recent: List<RecentSession> = emptyList(),
    val openCvAvailable: Boolean = true,
)

class HomeViewModel(
    private val repository: RangeRepository,
    openCvAvailable: Boolean,
) : ViewModel() {

    val state: StateFlow<HomeState> = combine(
        repository.observeProfiles(),
        repository.observeFirearms(),
        repository.observeAmmo(),
        repository.observeTargets(),
        repository.observeAnalysedSessions(),
    ) { profiles, firearms, ammo, targets, sessions ->
        val firearmsById = firearms.associateBy { it.id }
        val ammoById = ammo.associateBy { it.id }
        val targetsById = targets.associateBy { it.id }

        HomeState(
            profiles = profiles.map { profile ->
                ProfileSummary(
                    profile = profile,
                    firearmName = firearmsById[profile.firearmId]?.name,
                    ammoName = profile.ammoId?.let { ammoById[it]?.displayName },
                    targetName = targetsById[profile.targetSpecId]?.name,
                )
            },
            recent = sessions
                .sortedByDescending { it.session.startedAtEpochMs }
                .take(RECENT_LIMIT)
                .map { it.toRecent() },
            openCvAvailable = openCvAvailable,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeState(openCvAvailable = openCvAvailable),
    )

    fun markUsed(profile: Profile) {
        viewModelScope.launch {
            repository.markProfileUsed(profile.id, System.currentTimeMillis())
        }
    }

    private fun AnalysedSession.toRecent(): RecentSession {
        val stats = stats
        return RecentSession(
            id = session.id,
            name = session.name,
            subtitle = listOfNotNull(
                firearm?.name,
                ammo?.displayName,
                "${session.distanceM.toInt()} m",
                "${stats.shotCount} shots",
            ).joinToString("  ·  "),
            // Mean radius leads rather than group size: it uses every shot rather than the two
            // worst, so it is the number that actually tracks how you are shooting.
            headline = String.format(Locale.ROOT, "%.1f mm", stats.meanRadiusMm),
            headlineLabel = String.format(
                Locale.ROOT,
                "mean radius · %.2f MOA",
                Units.mmToMoa(stats.meanRadiusMm, session.distanceM),
            ),
        )
    }

    private companion object {
        const val RECENT_LIMIT = 8
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
