package com.nobrainsoft.rangeanalyser.core.coach

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.ballistics.Atmosphere
import com.nobrainsoft.rangeanalyser.core.ballistics.Ballistics
import com.nobrainsoft.rangeanalyser.core.model.ActionType
import com.nobrainsoft.rangeanalyser.core.model.Handedness
import com.nobrainsoft.rangeanalyser.core.model.ShotSource
import com.nobrainsoft.rangeanalyser.core.stats.GroupStats
import com.nobrainsoft.rangeanalyser.core.stats.SightAdjustment
import com.nobrainsoft.rangeanalyser.core.stats.TrendAnalysis
import com.nobrainsoft.rangeanalyser.core.stats.TrendDirection
import com.nobrainsoft.rangeanalyser.core.stats.TrendMetric
import com.nobrainsoft.rangeanalyser.core.stats.VerticalDirection
import com.nobrainsoft.rangeanalyser.core.stats.HorizontalDirection

/**
 * Everything a rule is allowed to look at.
 */
data class CoachingContext(
    val session: AnalysedSession,
    /** Earlier sessions with the same profile, for progress comparison. Oldest first. */
    val history: List<AnalysedSession> = emptyList(),
) {
    val stats: GroupStats get() = session.stats
}

fun interface CoachingRule {
    fun evaluate(context: CoachingContext): List<Tip>
}

/**
 * Turns a group into advice.
 *
 * The engine tries hard to separate three things that shooters habitually run together: where the
 * group is (a sight setting), how big it is (technique and ammunition together), and what the data
 * can actually support (usually less than people think). Rules that reach into shooting folklore
 * are marked [TipConfidence.LOW] and say so in their wording.
 */
object CoachingEngine {

    // Thresholds live here rather than scattered through the rules, so they can be seen, argued
    // with and tuned in one place.
    private const val STRINGING_RATIO = 1.8
    private const val DIAGONAL_ELONGATION = 1.7
    private const val DIAGONAL_BEARING_TOLERANCE = 25.0
    private const val COLD_BORE_RATIO = 2.0
    private const val DRIFT_STRENGTH = 0.6
    private const val FATIGUE_RATIO = 1.5
    private const val AMMO_LIMIT_FRACTION = 0.8
    private const val AMMO_MINOR_FRACTION = 0.35
    private const val MIN_SHOTS_FOR_SHAPE = 5
    private const val LOW_CONFIDENCE_DETECTION = 0.6

    val defaultRules: List<CoachingRule> = listOf(
        ZeroRule,
        VerticalStringingRule,
        HorizontalStringingRule,
        DiagonalGroupRule,
        AmmunitionCeilingRule,
        FlyerRule,
        ColdBoreRule,
        BarrelWalkRule,
        FatigueRule,
        SmallSampleRule,
        SpringPistonRule,
        DetectionQualityRule,
        ProgressRule,
    )

    fun analyse(
        context: CoachingContext,
        rules: List<CoachingRule> = defaultRules,
    ): CoachingReport {
        val tips = rules
            .flatMap { runCatching { it.evaluate(context) }.getOrDefault(emptyList()) }
            // Loudest first, and within a severity the best-supported first: TipConfidence is
            // declared HIGH, MEDIUM, LOW, so ascending ordinal is descending confidence.
            .sortedWith(
                compareByDescending<Tip> { it.severity.ordinal }
                    .thenBy { it.confidence.ordinal }
                    .thenBy { it.id },
            )
        return CoachingReport(tips)
    }

    // --- Zero -----------------------------------------------------------------------------------

    private object ZeroRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val stats = context.stats
            val offset = stats.centroidOffset
            val clickValue = context.session.firearm?.sight?.clickValue

            if (!SightAdjustment.offsetIsSignificant(stats)) {
                if (stats.shotCount < 3) return emptyList()
                return listOf(
                    Tip(
                        id = "zero.centred",
                        title = "Zero looks good",
                        category = TipCategory.ZERO,
                        severity = TipSeverity.INFO,
                        confidence = TipConfidence.HIGH,
                        body = "The group is centred close enough to your aim point that the offset " +
                            "is within the spread of the group itself. There is nothing to correct " +
                            "here - adjusting now would be chasing noise.",
                        evidence = listOf(
                            Evidence("Group centre offset", Format.mm(offset.radius)),
                            Evidence("Group mean radius", Format.mm(stats.meanRadiusMm)),
                        ),
                    ),
                )
            }

            val direction = "${Format.mm(kotlin.math.abs(offset.y))} " +
                (if (offset.y > 0) "high" else "low") +
                " and ${Format.mm(kotlin.math.abs(offset.x))} " +
                (if (offset.x > 0) "right" else "left")

            if (clickValue == null) {
                return listOf(
                    Tip(
                        id = "zero.offset.noclicks",
                        title = "Your zero is off",
                        category = TipCategory.ZERO,
                        severity = TipSeverity.IMPORTANT,
                        confidence = TipConfidence.HIGH,
                        body = "The group sits $direction of your aim point, and it is a real offset " +
                            "rather than scatter. Add your sight's click value in the firearm " +
                            "library and the app will work out the exact correction for you.",
                        evidence = listOf(
                            Evidence("Group centre offset", Format.mm(offset.radius)),
                            Evidence("Direction from aim point", Format.clock(offset.clockPosition())),
                        ),
                    ),
                )
            }

            val correction = SightAdjustment.compute(stats, clickValue)
            if (correction.isNoOp) return emptyList()

            val instructions = buildList {
                if (correction.verticalDirection != VerticalDirection.NONE) {
                    add(
                        "${correction.verticalClickCount} " +
                            (if (correction.verticalDirection == VerticalDirection.UP) "up" else "down"),
                    )
                }
                if (correction.horizontalDirection != HorizontalDirection.NONE) {
                    add(
                        "${correction.horizontalClickCount} " +
                            (if (correction.horizontalDirection == HorizontalDirection.RIGHT) "right" else "left"),
                    )
                }
            }.joinToString(", ")

            return listOf(
                Tip(
                    id = "zero.offset",
                    title = "Adjust your sights: $instructions",
                    category = TipCategory.ZERO,
                    severity = TipSeverity.IMPORTANT,
                    confidence = TipConfidence.HIGH,
                    body = "The group sits $direction of your aim point. Move the point of impact " +
                        "$instructions. Sight markings vary, so if the group moves the wrong way " +
                        "after the first adjustment, reverse the direction - the click count is right " +
                        "either way.",
                    evidence = listOf(
                        Evidence("Group centre offset", Format.mm(offset.radius)),
                        Evidence("One click at ${Format.round(stats.distanceM, 0)} m", Format.mm(correction.clickValueMm)),
                        Evidence("Left over after adjusting", Format.mm(correction.residualMm.radius)),
                    ),
                ),
            )
        }
    }

    // --- Group shape -----------------------------------------------------------------------------

    private object VerticalStringingRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val stats = context.stats
            if (stats.shotCount < MIN_SHOTS_FOR_SHAPE) return emptyList()
            if (stats.verticalToHorizontalRatio < STRINGING_RATIO) return emptyList()

            val firearm = context.session.firearm
            val ammoShare = ammunitionShareOfVertical(context)

            val explanation = when {
                ammoShare != null && ammoShare >= AMMO_LIMIT_FRACTION ->
                    "Your ammunition alone accounts for about ${Format.percent(ammoShare)} of it. " +
                        "This is a load problem, not a technique problem - a more consistent load " +
                        "is the only thing that will tighten it."

                ammoShare != null && ammoShare <= AMMO_MINOR_FRACTION ->
                    "Your ammunition only accounts for about ${Format.percent(ammoShare)} of it, so " +
                        "most of this is coming from somewhere else."

                firearm?.isPistol == true ->
                    "On a pistol this usually comes from grip pressure changing between shots, or " +
                        "from the wrist giving differently under recoil."

                else ->
                    "Common causes are breathing not settled at the shot, inconsistent shoulder or " +
                        "cheek pressure, and parallax if you are using a scope."
            }

            return listOf(
                Tip(
                    id = "shape.vertical",
                    title = "Group is stringing vertically",
                    category = if (ammoShare != null && ammoShare >= AMMO_LIMIT_FRACTION) {
                        TipCategory.AMMUNITION
                    } else {
                        TipCategory.TECHNIQUE
                    },
                    severity = TipSeverity.SUGGESTION,
                    confidence = if (ammoShare != null) TipConfidence.MEDIUM else TipConfidence.LOW,
                    body = "The group is ${Format.ratio(stats.verticalToHorizontalRatio)} taller than " +
                        "it is wide. $explanation",
                    evidence = buildList {
                        add(Evidence("Vertical spread", Format.mm(stats.sigmaYMm)))
                        add(Evidence("Horizontal spread", Format.mm(stats.sigmaXMm)))
                        ammoShare?.let {
                            add(Evidence("Explained by velocity spread", Format.percent(it)))
                        }
                    },
                ),
            )
        }
    }

    private object HorizontalStringingRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val stats = context.stats
            if (stats.shotCount < MIN_SHOTS_FOR_SHAPE) return emptyList()
            if (stats.verticalToHorizontalRatio > 1.0 / STRINGING_RATIO) return emptyList()

            val wind = context.session.session.weather?.windSpeedMps
            val windNote = if (wind != null && wind > 1.0) {
                " You recorded ${Format.round(wind, 1)} m/s of wind, which would do exactly this."
            } else {
                ""
            }

            val technique = if (context.session.firearm?.isPistol == true) {
                "On a pistol, horizontal spread most often traces back to trigger finger placement - " +
                    "too much or too little finger pushes the shot sideways as the trigger breaks."
            } else {
                "Check your natural point of aim: if the rifle wants to sit somewhere other than the " +
                    "target, you spend the shot muscling it back, and it moves as the trigger breaks."
            }

            return listOf(
                Tip(
                    id = "shape.horizontal",
                    title = "Group is stringing horizontally",
                    category = TipCategory.TECHNIQUE,
                    severity = TipSeverity.SUGGESTION,
                    confidence = TipConfidence.LOW,
                    body = "The group is wider than it is tall.$windNote $technique",
                    evidence = listOf(
                        Evidence("Horizontal spread", Format.mm(stats.sigmaXMm)),
                        Evidence("Vertical spread", Format.mm(stats.sigmaYMm)),
                    ),
                ),
            )
        }
    }

    private object DiagonalGroupRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val stats = context.stats
            if (stats.shotCount < MIN_SHOTS_FOR_SHAPE) return emptyList()
            if (stats.axes.elongation < DIAGONAL_ELONGATION) return emptyList()

            val bearing = stats.axes.majorAxisBearingDeg
            val nearDiagonal = kotlin.math.abs(bearing - 45.0) < DIAGONAL_BEARING_TOLERANCE ||
                kotlin.math.abs(bearing - 135.0) < DIAGONAL_BEARING_TOLERANCE
            if (!nearDiagonal) return emptyList()
            if (context.session.firearm?.isPistol != true) return emptyList()

            val handedness = context.session.session.handedness
            val lowSide = when (handedness) {
                Handedness.RIGHT -> "low and left"
                Handedness.LEFT -> "low and right"
                Handedness.UNKNOWN -> "diagonally, low towards your trigger-hand side"
            }

            return listOf(
                Tip(
                    id = "shape.diagonal",
                    title = "Group runs on a diagonal",
                    category = TipCategory.TECHNIQUE,
                    severity = TipSeverity.SUGGESTION,
                    confidence = TipConfidence.LOW,
                    body = "The group is stretched along a diagonal rather than being round or " +
                        "stringing along one axis. Pistol shooters traditionally read a group that " +
                        "runs $lowSide as anticipating recoil - pushing into the shot as the trigger " +
                        "breaks. That association is shooting lore rather than measured fact, so " +
                        "treat it as something to test: mix a few dummy rounds into a magazine and " +
                        "watch whether the muzzle dips on the ones that do not fire.",
                    evidence = listOf(
                        Evidence("Long axis bearing", "${Format.round(bearing, 0)}° from vertical"),
                        Evidence("Elongation", Format.ratio(stats.axes.elongation)),
                    ),
                ),
            )
        }
    }

    // --- Ammunition -------------------------------------------------------------------------------

    private object AmmunitionCeilingRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val share = ammunitionShareOfVertical(context) ?: return emptyList()
            if (share < AMMO_LIMIT_FRACTION) return emptyList()
            // The vertical stringing rule already says this when the group is visibly tall.
            if (context.stats.verticalToHorizontalRatio >= STRINGING_RATIO) return emptyList()

            val ammo = context.session.ammo ?: return emptyList()

            return listOf(
                Tip(
                    id = "ammo.ceiling",
                    title = "You are at this load's limit",
                    category = TipCategory.AMMUNITION,
                    severity = TipSeverity.INFO,
                    confidence = TipConfidence.MEDIUM,
                    body = "The velocity spread you recorded for ${ammo.displayName} accounts for " +
                        "about ${Format.percent(share)} of the vertical spread in this group at " +
                        "${Format.round(context.stats.distanceM, 0)} m. Technique work will not " +
                        "tighten this much further; better ammunition would.",
                    evidence = listOf(
                        Evidence("Velocity SD", "${Format.round(ammo.velocitySdMps ?: 0.0, 1)} m/s"),
                        Evidence("Vertical spread measured", Format.mm(context.stats.sigmaYMm)),
                    ),
                ),
            )
        }
    }

    // --- Order-dependent -----------------------------------------------------------------------

    private object ColdBoreRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val order = context.stats.order ?: return emptyList()
            val ratio = order.coldBoreOffsetRatio ?: return emptyList()
            if (ratio < COLD_BORE_RATIO) return emptyList()

            return listOf(
                Tip(
                    id = "order.coldbore",
                    title = "First shot landed away from the rest",
                    category = TipCategory.EQUIPMENT,
                    severity = TipSeverity.INFO,
                    confidence = TipConfidence.MEDIUM,
                    body = "Your first shot was ${Format.mm(order.coldBoreOffsetMm ?: 0.0)} from the " +
                        "centre of the others - well outside the rest of the group. A repeatable " +
                        "cold-bore offset is worth knowing if the first shot is the one that counts. " +
                        "Watch whether it lands in the same place next time before treating it as " +
                        "real; one string cannot tell you.",
                    evidence = listOf(
                        Evidence("First shot from group centre", Format.mm(order.coldBoreOffsetMm ?: 0.0)),
                        Evidence("Relative to the rest of the group", Format.ratio(ratio)),
                    ),
                ),
            )
        }
    }

    private object BarrelWalkRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val order = context.stats.order ?: return emptyList()
            if (order.driftStrength < DRIFT_STRENGTH) return emptyList()
            if (order.driftPerShotMm <= 0.0) return emptyList()

            val isRifle = context.session.firearm?.isRifle == true
            val cause = if (isRifle) {
                "This is the classic signature of a barrel heating up, or of a bipod or rest settling " +
                    "under recoil. Try longer pauses between shots and check that nothing is shifting."
            } else {
                "Something is moving steadily through the string - your stance settling, or your grip " +
                    "creeping."
            }

            return listOf(
                Tip(
                    id = "order.walk",
                    title = "Point of impact walked across the string",
                    category = TipCategory.EQUIPMENT,
                    severity = TipSeverity.SUGGESTION,
                    confidence = TipConfidence.MEDIUM,
                    body = "The point of impact moved steadily as the string went on, rather than " +
                        "scattering randomly. $cause",
                    evidence = listOf(
                        Evidence("Drift", Format.mmPerShot(order.driftPerShotMm)),
                        Evidence("How much of the spread it explains", Format.percent(order.driftStrength)),
                    ),
                ),
            )
        }
    }

    private object FatigueRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val order = context.stats.order ?: return emptyList()
            if (order.firstHalfMeanRadiusMm <= 0.0) return emptyList()
            val ratio = order.secondHalfMeanRadiusMm / order.firstHalfMeanRadiusMm
            if (ratio < FATIGUE_RATIO) return emptyList()

            return listOf(
                Tip(
                    id = "order.fatigue",
                    title = "The second half of the string opened up",
                    category = TipCategory.TECHNIQUE,
                    severity = TipSeverity.SUGGESTION,
                    confidence = TipConfidence.MEDIUM,
                    body = "Your later shots scattered noticeably more than your early ones. Shorter " +
                        "strings with a break between them will usually tighten the average, and are " +
                        "worth more than pushing through a long one.",
                    evidence = listOf(
                        Evidence("First half mean radius", Format.mm(order.firstHalfMeanRadiusMm)),
                        Evidence("Second half mean radius", Format.mm(order.secondHalfMeanRadiusMm)),
                    ),
                ),
            )
        }
    }

    private object FlyerRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val stats = context.stats
            val flyers = stats.flyers
            if (flyers.isEmpty()) return emptyList()

            val remaining = context.session.session.shots
                .filterIndexed { index, _ -> flyers.none { it.shotIndex == index } }
            val without = GroupStats.of(remaining, stats.distanceM)

            val comparison = without?.let {
                " Without ${if (flyers.size == 1) "it" else "them"} the mean radius is " +
                    "${Format.mm(it.meanRadiusMm)} instead of ${Format.mm(stats.meanRadiusMm)}."
            } ?: ""

            return listOf(
                Tip(
                    id = "stats.flyer",
                    title = if (flyers.size == 1) "One shot does not fit the group" else "${flyers.size} shots do not fit the group",
                    category = TipCategory.STATISTICS,
                    severity = TipSeverity.INFO,
                    confidence = TipConfidence.MEDIUM,
                    body = "Shot ${flyers.joinToString(", ") { (it.orderIndex + 1).toString() }} sits " +
                        "further out than the spread of the rest can readily explain.$comparison " +
                        "Both numbers are shown because dropping a shot you did not call as a bad one " +
                        "flatters the result - if you did call it, the smaller figure is the honest one.",
                    evidence = flyers.map {
                        Evidence("Shot ${it.orderIndex + 1}", "outside the group's own spread")
                    },
                ),
            )
        }
    }

    // --- What the data supports -----------------------------------------------------------------

    private object SmallSampleRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val stats = context.stats
            if (!stats.isSmallSample) return emptyList()

            val interval = stats.extremeSpreadCi
            val range = interval?.let {
                " Groups of this size from the same rifle and load would typically measure anywhere " +
                    "between ${Format.mm(it.lower)} and ${Format.mm(it.upper)}."
            } ?: ""

            return listOf(
                Tip(
                    id = "stats.smallsample",
                    title = "Too few shots to conclude much",
                    category = TipCategory.STATISTICS,
                    severity = TipSeverity.IMPORTANT,
                    confidence = TipConfidence.HIGH,
                    body = "This group has ${stats.shotCount} shots.$range That range is wide enough " +
                        "that a group this size cannot tell a good load from a mediocre one. Two or " +
                        "three strings of five, compared together, will tell you far more than one " +
                        "lucky group.",
                    evidence = buildList {
                        add(Evidence("Shots", stats.shotCount.toString()))
                        add(Evidence("Measured group", Format.mm(stats.extremeSpreadMm)))
                        interval?.let {
                            add(Evidence("Plausible range", "${Format.mm(it.lower)} to ${Format.mm(it.upper)}"))
                        }
                    },
                ),
            )
        }
    }

    private object DetectionQualityRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val shots = context.session.session.shots.filterNot { it.excluded }
            if (shots.isEmpty()) return emptyList()

            val doubtful = shots.filter {
                it.source == ShotSource.AUTO && it.confidence < LOW_CONFIDENCE_DETECTION
            }
            if (doubtful.isEmpty()) return emptyList()

            return listOf(
                Tip(
                    id = "detection.review",
                    title = "Check ${doubtful.size} uncertain hole${if (doubtful.size == 1) "" else "s"}",
                    category = TipCategory.DETECTION,
                    severity = TipSeverity.IMPORTANT,
                    confidence = TipConfidence.HIGH,
                    body = "The detector was not confident about " +
                        "${doubtful.size} of the ${shots.size} holes it found. Every number on this " +
                        "screen is built on those positions, so it is worth a look before you read " +
                        "anything into them.",
                    evidence = doubtful.map {
                        Evidence("Shot ${it.orderIndex + 1}", "${Format.percent(it.confidence)} confidence")
                    },
                ),
            )
        }
    }

    private object SpringPistonRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            val firearm = context.session.firearm ?: return emptyList()
            if (firearm.action != ActionType.SPRING_PISTON) return emptyList()
            if (context.stats.shotCount < MIN_SHOTS_FOR_SHAPE) return emptyList()

            return listOf(
                Tip(
                    id = "equipment.springer",
                    title = "Spring guns reward a loose hold",
                    category = TipCategory.TECHNIQUE,
                    severity = TipSeverity.INFO,
                    confidence = TipConfidence.MEDIUM,
                    body = "A spring-piston airgun recoils in two directions before the pellet leaves " +
                        "the barrel, so how firmly you hold it changes where it shoots. Resting it " +
                        "on your open palm and letting it move freely usually groups better than " +
                        "gripping it hard or laying it on a solid rest.",
                    evidence = listOf(Evidence("Action", "spring piston")),
                ),
            )
        }
    }

    private object ProgressRule : CoachingRule {
        override fun evaluate(context: CoachingContext): List<Tip> {
            if (context.history.isEmpty()) return emptyList()
            val series = context.history + context.session
            val trend = TrendAnalysis.over(series, TrendMetric.MEAN_RADIUS_MM) ?: return emptyList()

            return when (trend.direction) {
                TrendDirection.IMPROVING -> listOf(
                    Tip(
                        id = "progress.improving",
                        title = "You are getting tighter",
                        category = TipCategory.PROGRESS,
                        severity = TipSeverity.INFO,
                        confidence = TipConfidence.HIGH,
                        body = "Across your last ${trend.sessionCount} sessions with this setup, mean " +
                            "radius has been falling by about " +
                            "${Format.mmPerSession(kotlin.math.abs(trend.slopePerSession))} - and the " +
                            "trend holds up rather than being one good day.",
                        evidence = listOf(
                            Evidence("Sessions", trend.sessionCount.toString()),
                            Evidence("First to latest", "${Format.mm(trend.firstValue)} to ${Format.mm(trend.lastValue)}"),
                        ),
                    ),
                )

                TrendDirection.WORSENING -> listOf(
                    Tip(
                        id = "progress.worsening",
                        title = "Groups have been opening up",
                        category = TipCategory.PROGRESS,
                        severity = TipSeverity.SUGGESTION,
                        confidence = TipConfidence.HIGH,
                        body = "Mean radius has been growing across your last ${trend.sessionCount} " +
                            "sessions with this setup. Worth checking the boring things first: " +
                            "mounting screws, scope rings, and whether the ammunition is from the " +
                            "same lot.",
                        evidence = listOf(
                            Evidence("Sessions", trend.sessionCount.toString()),
                            Evidence("First to latest", "${Format.mm(trend.firstValue)} to ${Format.mm(trend.lastValue)}"),
                        ),
                    ),
                )

                TrendDirection.NO_CHANGE_DETECTED -> listOf(
                    Tip(
                        id = "progress.flat",
                        title = "No clear change yet",
                        category = TipCategory.PROGRESS,
                        severity = TipSeverity.INFO,
                        confidence = TipConfidence.HIGH,
                        body = "Across ${trend.sessionCount} sessions your groups have moved around, " +
                            "but not by more than normal session-to-session variation. That is not the " +
                            "same as no progress - it means there is not enough data to see it yet.",
                        evidence = listOf(Evidence("Sessions", trend.sessionCount.toString())),
                    ),
                )
            }
        }
    }

    /**
     * What fraction of the observed vertical spread the load's velocity spread explains.
     *
     * Null whenever the ammunition record lacks the velocity, SD or BC needed - the engine stays
     * quiet rather than guessing at the numbers.
     */
    private fun ammunitionShareOfVertical(context: CoachingContext): Double? {
        val ammo = context.session.ammo ?: return null
        val stats = context.stats
        if (stats.sigmaYMm <= 0.0) return null

        val zeroDistance = context.session.firearm?.zeroDistanceM ?: stats.distanceM
        val dispersion = Ballistics.verticalDispersionFromVelocitySd(
            ammo = ammo,
            distanceM = stats.distanceM,
            zeroDistanceM = zeroDistance,
            sightHeightMm = context.session.firearm?.sight?.opticHeightMm
                ?: Ballistics.DEFAULT_SIGHT_HEIGHT_MM,
            atmosphere = Atmosphere.from(context.session.session.weather),
        ) ?: return null

        return dispersion.fractionOf(stats.sigmaYMm)
    }
}
