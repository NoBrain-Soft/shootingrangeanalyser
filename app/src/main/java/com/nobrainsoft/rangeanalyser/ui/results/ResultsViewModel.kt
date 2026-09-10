package com.nobrainsoft.rangeanalyser.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.coach.CoachingContext
import com.nobrainsoft.rangeanalyser.core.coach.CoachingEngine
import com.nobrainsoft.rangeanalyser.core.coach.CoachingReport
import com.nobrainsoft.rangeanalyser.core.stats.SightAdjustment
import com.nobrainsoft.rangeanalyser.core.stats.SightCorrection
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ResultsState(
    val analysed: AnalysedSession? = null,
    val coaching: CoachingReport? = null,
    val correction: SightCorrection? = null,
)

class ResultsViewModel(
    repository: RangeRepository,
    private val sessionId: String,
) : ViewModel() {

    val state: StateFlow<ResultsState> = repository.observeAnalysedSessions()
        .map { all ->
            val analysed = all.firstOrNull { it.session.id == sessionId }
                ?: return@map ResultsState()

            // Coaching sees the shooter's own history with this setup, so it can talk about
            // progress rather than only about today.
            val history = all
                .filter {
                    it.session.id != sessionId &&
                        it.session.firearmId == analysed.session.firearmId &&
                        it.session.distanceM == analysed.session.distanceM
                }
                .sortedBy { it.session.startedAtEpochMs }

            ResultsState(
                analysed = analysed,
                coaching = CoachingEngine.analyse(CoachingContext(analysed, history)),
                // Only offered when the offset is real and the sight's click value is known:
                // a click count derived from noise would make the zero worse.
                correction = analysed.firearm?.sight?.clickValue
                    ?.takeIf { SightAdjustment.offsetIsSignificant(analysed.stats) }
                    ?.let { SightAdjustment.compute(analysed.stats, it) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultsState())
}
