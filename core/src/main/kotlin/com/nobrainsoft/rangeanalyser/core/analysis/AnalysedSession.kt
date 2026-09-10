package com.nobrainsoft.rangeanalyser.core.analysis

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.scoring
import com.nobrainsoft.rangeanalyser.core.scoring.ScoreSummary
import com.nobrainsoft.rangeanalyser.core.scoring.Scorer
import com.nobrainsoft.rangeanalyser.core.stats.GroupStats
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec

/**
 * A session with its analysis attached.
 *
 * Computing statistics is not free, and the history, comparison and coaching screens all want the
 * same numbers, so they are worked out once and passed around together.
 */
data class AnalysedSession(
    val session: Session,
    val stats: GroupStats,
    val score: ScoreSummary?,
    val firearm: Firearm?,
    val ammo: Ammo?,
    val target: TargetSpec?,
) {
    val positions: List<PointMm> get() = session.shots.scoring().map { it.position }

    val shotCount: Int get() = stats.shotCount

    companion object {
        /** Returns null when the session has no usable shots. */
        fun analyse(
            session: Session,
            firearm: Firearm?,
            ammo: Ammo?,
            target: TargetSpec?,
        ): AnalysedSession? {
            val caliber = firearm?.caliber()
            val stats = GroupStats.of(
                shots = session.shots,
                distanceM = session.distanceM,
                caliber = caliber,
                pointOfAim = session.pointOfAim,
            ) ?: return null

            val score = target?.let { Scorer(it, caliber).summarise(session.shots) }

            return AnalysedSession(
                session = session,
                stats = stats,
                score = score,
                firearm = firearm,
                ammo = ammo,
                target = target,
            )
        }
    }
}
