package com.nobrainsoft.rangeanalyser.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.stats.AmmoRanking
import com.nobrainsoft.rangeanalyser.core.stats.ComparisonResult
import com.nobrainsoft.rangeanalyser.core.stats.GroupComparison
import com.nobrainsoft.rangeanalyser.core.stats.RankingResult
import com.nobrainsoft.rangeanalyser.core.stats.Trend
import com.nobrainsoft.rangeanalyser.core.stats.TrendAnalysis
import com.nobrainsoft.rangeanalyser.core.stats.TrendMetric
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryFilter(
    val firearmId: String? = null,
    val ammoId: String? = null,
    val favouritesOnly: Boolean = false,
)

data class HistoryState(
    val sessions: List<AnalysedSession> = emptyList(),
    val filter: HistoryFilter = HistoryFilter(),
    val selectedIds: List<String> = emptyList(),
    val comparison: ComparisonResult? = null,
    val trend: Trend? = null,
    val trendMetric: TrendMetric = TrendMetric.MEAN_RADIUS_MM,
    val ranking: RankingResult? = null,
) {
    val selected: List<AnalysedSession>
        get() = selectedIds.mapNotNull { id -> sessions.firstOrNull { it.session.id == id } }

    val canCompare: Boolean get() = selectedIds.size >= 2
}

/**
 * Backs history, comparison and progress.
 *
 * They are one view model because they are one question asked three ways: what have I shot, is this
 * better than that, and am I getting better overall.
 */
class HistoryViewModel(private val repository: RangeRepository) : ViewModel() {

    private val filter = MutableStateFlow(HistoryFilter())
    private val selection = MutableStateFlow<List<String>>(emptyList())
    private val metric = MutableStateFlow(TrendMetric.MEAN_RADIUS_MM)

    val state: StateFlow<HistoryState> = combine(
        repository.observeAnalysedSessions(),
        filter,
        selection,
        metric,
    ) { all, currentFilter, selectedIds, trendMetric ->
        val filtered = all
            .filter { currentFilter.firearmId == null || it.session.firearmId == currentFilter.firearmId }
            .filter { currentFilter.ammoId == null || it.session.ammoId == currentFilter.ammoId }
            .filter { !currentFilter.favouritesOnly || it.session.isFavourite }
            .sortedByDescending { it.session.startedAtEpochMs }

        val chosen = selectedIds.mapNotNull { id -> all.firstOrNull { it.session.id == id } }

        HistoryState(
            sessions = filtered,
            filter = currentFilter,
            selectedIds = selectedIds,
            comparison = if (chosen.size >= 2) {
                GroupComparison.compare(chosen[0], chosen[1])
            } else {
                null
            },
            // Trends need a consistent setup, or they measure the change of kit rather than of
            // the shooter.
            trend = TrendAnalysis.over(
                filtered.sortedBy { it.session.startedAtEpochMs },
                trendMetric,
            ),
            trendMetric = trendMetric,
            ranking = AmmoRanking.rank(filtered),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun toggleSelected(sessionId: String) {
        val current = selection.value
        selection.value = when {
            sessionId in current -> current - sessionId
            // Comparison is between two groups; a third selection replaces the older one rather
            // than silently doing nothing.
            current.size >= 2 -> listOf(current.last(), sessionId)
            else -> current + sessionId
        }
    }

    fun clearSelection() {
        selection.value = emptyList()
    }

    fun setFirearmFilter(firearmId: String?) {
        filter.value = filter.value.copy(firearmId = firearmId)
    }

    fun setAmmoFilter(ammoId: String?) {
        filter.value = filter.value.copy(ammoId = ammoId)
    }

    fun toggleFavouritesOnly() {
        filter.value = filter.value.copy(favouritesOnly = !filter.value.favouritesOnly)
    }

    fun setMetric(value: TrendMetric) {
        metric.value = value
    }

    fun setFavourite(sessionId: String, favourite: Boolean) {
        viewModelScope.launch { repository.setFavourite(sessionId, favourite) }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }
}
