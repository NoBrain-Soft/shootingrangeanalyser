package com.nobrainsoft.rangeanalyser.core.coach

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.model.ActionType
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.ClickUnit
import com.nobrainsoft.rangeanalyser.core.model.ClickValue
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.FirearmType
import com.nobrainsoft.rangeanalyser.core.model.Handedness
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.SessionMode
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.ShotSource
import com.nobrainsoft.rangeanalyser.core.model.SightSetup
import com.nobrainsoft.rangeanalyser.core.model.SightType
import com.nobrainsoft.rangeanalyser.core.stats.Statistics
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoachingEngineTest {

    // --- Zero ------------------------------------------------------------------------------------

    @Test
    fun `an off zero produces a click count`() {
        val offset = PointMm(-20.0, 60.0)
        val report = analyse(tightGroup(12, at = offset), firearm = scopedRifle)

        val tip = assertNotNull(report.tips.find { it.id == "zero.offset" })
        assertEquals(TipSeverity.IMPORTANT, tip.severity)
        assertEquals(TipConfidence.HIGH, tip.confidence)
        // 60 mm high and 20 mm left at 100 m with 10 mm clicks.
        assertTrue(tip.title.contains("6 down"), "expected 6 clicks down, got '${tip.title}'")
        assertTrue(tip.title.contains("2 right"), "expected 2 clicks right, got '${tip.title}'")
    }

    @Test
    fun `a centred group is told to leave the sights alone`() {
        val report = analyse(tightGroup(12, at = PointMm.ORIGIN), firearm = scopedRifle)

        assertNotNull(report.tips.find { it.id == "zero.centred" })
        assertTrue(report.tips.none { it.id == "zero.offset" })
    }

    @Test
    fun `a scattered group barely off centre is not sent to adjust sights`() {
        // The offset is real-looking but well inside the group's own spread. Telling somebody to
        // adjust here would make their zero worse.
        val report = analyse(
            gaussianGroup(5, sigma = 40.0, seed = 3, centre = PointMm(8.0, 8.0)),
            firearm = scopedRifle,
        )
        assertTrue(report.tips.none { it.id == "zero.offset" })
    }

    @Test
    fun `without a click value the app asks for one instead of guessing`() {
        val ironsWithoutClicks = scopedRifle.copy(sight = SightSetup(type = SightType.OPEN_IRON))
        val report = analyse(tightGroup(12, at = PointMm(0.0, 60.0)), firearm = ironsWithoutClicks)

        val tip = assertNotNull(report.tips.find { it.id == "zero.offset.noclicks" })
        assertTrue(tip.body.contains("click value"))
    }

    // --- Shape -----------------------------------------------------------------------------------

    @Test
    fun `a vertically strung group is identified`() {
        val report = analyse(strungGroup(8, verticalSigma = 30.0, horizontalSigma = 6.0))

        val tip = assertNotNull(report.tips.find { it.id == "shape.vertical" })
        assertTrue(tip.evidence.any { it.label == "Vertical spread" })
    }

    @Test
    fun `vertical stringing is blamed on the ammunition when the ammunition explains it`() {
        // Bulk ammunition with a 55 fps velocity SD, at 600 yards. The load alone throws about
        // 130 mm of vertical there, more than the whole observed spread, so the advice must point
        // at the ammunition rather than at the shooter's breathing.
        val distance = Units.yardsToMetres(600.0)
        val report = analyse(
            strungGroup(10, verticalSigma = 120.0, horizontalSigma = 30.0),
            firearm = scopedRifle,
            ammo = sloppyAmmo,
            distanceM = distance,
        )

        val tip = assertNotNull(report.tips.find { it.id == "shape.vertical" })
        assertEquals(TipCategory.AMMUNITION, tip.category)
        assertTrue(
            tip.body.contains("load problem"),
            "should point at the load, said: ${tip.body}",
        )
        assertTrue(tip.evidence.any { it.label == "Explained by velocity spread" })
    }

    @Test
    fun `vertical stringing stays a technique note when the ammunition cannot explain it`() {
        // Same sloppy load, but at 100 m where velocity spread has almost no time to act.
        val report = analyse(
            strungGroup(10, verticalSigma = 60.0, horizontalSigma = 12.0),
            firearm = scopedRifle,
            ammo = sloppyAmmo,
            distanceM = Units.yardsToMetres(100.0),
        )

        val tip = assertNotNull(report.tips.find { it.id == "shape.vertical" })
        assertEquals(TipCategory.TECHNIQUE, tip.category)
        assertTrue(tip.body.contains("somewhere else"), "said: ${tip.body}")
    }

    @Test
    fun `a horizontally strung group mentions recorded wind`() {
        val report = analyse(
            strungGroup(8, verticalSigma = 6.0, horizontalSigma = 30.0),
            firearm = scopedRifle,
            weather = com.nobrainsoft.rangeanalyser.core.model.Weather(windSpeedMps = 5.0),
        )

        val tip = assertNotNull(report.tips.find { it.id == "shape.horizontal" })
        assertTrue(tip.body.contains("5.0 m/s"), "should cite the wind you logged: ${tip.body}")
    }

    @Test
    fun `a diagonal pistol group gets the folklore tip, clearly labelled as folklore`() {
        val diagonal = (0 until 8).map { PointMm(-it * 6.0, -it * 6.0) }
        val report = analyse(diagonal, firearm = pistol, handedness = Handedness.RIGHT)

        val tip = assertNotNull(report.tips.find { it.id == "shape.diagonal" })
        assertEquals(TipConfidence.LOW, tip.confidence)
        assertTrue(tip.body.contains("lore"), "must not present this as fact: ${tip.body}")
        assertTrue(tip.body.contains("low and left"), "right-handed phrasing expected")
    }

    @Test
    fun `the diagonal tip avoids assuming a side when handedness is unknown`() {
        val diagonal = (0 until 8).map { PointMm(-it * 6.0, -it * 6.0) }
        val report = analyse(diagonal, firearm = pistol, handedness = Handedness.UNKNOWN)

        val tip = assertNotNull(report.tips.find { it.id == "shape.diagonal" })
        assertTrue(tip.body.contains("trigger-hand side"))
    }

    @Test
    fun `a round group gets no stringing advice at all`() {
        val report = analyse(gaussianGroup(10, sigma = 15.0, seed = 9), firearm = scopedRifle)

        assertTrue(report.tips.none { it.id == "shape.vertical" })
        assertTrue(report.tips.none { it.id == "shape.horizontal" })
        assertTrue(report.tips.none { it.id == "shape.diagonal" })
    }

    // --- Order-dependent --------------------------------------------------------------------------

    @Test
    fun `a walking point of impact is called out`() {
        val walking = (0 until 10).map { PointMm(it * 2.5, it * 3.5) }
        val report = analyse(walking, firearm = scopedRifle)

        val tip = assertNotNull(report.tips.find { it.id == "order.walk" })
        assertTrue(tip.body.contains("barrel heating"), "rifle wording expected: ${tip.body}")
    }

    @Test
    fun `a cold bore shot is reported with a caution about one string`() {
        val positions = listOf(PointMm(0.0, 50.0)) +
            gaussianGroup(9, sigma = 5.0, seed = 12)
        val report = analyse(positions, firearm = scopedRifle)

        val tip = assertNotNull(report.tips.find { it.id == "order.coldbore" })
        assertTrue(tip.body.contains("one string cannot tell you"))
    }

    @Test
    fun `a string that opens up in the second half suggests shorter strings`() {
        val first = gaussianGroup(6, sigma = 5.0, seed = 21)
        val second = gaussianGroup(6, sigma = 25.0, seed = 22)
        val report = analyse(first + second, firearm = scopedRifle)

        assertNotNull(report.tips.find { it.id == "order.fatigue" })
    }

    // --- Honesty ------------------------------------------------------------------------------------

    @Test
    fun `a small sample is flagged loudly with the range it could really be`() {
        val report = analyse(gaussianGroup(3, sigma = 12.0, seed = 31), firearm = scopedRifle)

        val tip = assertNotNull(report.tips.find { it.id == "stats.smallsample" })
        assertEquals(TipSeverity.IMPORTANT, tip.severity)
    }

    @Test
    fun `a proper string is not nagged about sample size`() {
        val report = analyse(gaussianGroup(20, sigma = 12.0, seed = 32), firearm = scopedRifle)
        assertTrue(report.tips.none { it.id == "stats.smallsample" })
    }

    @Test
    fun `uncertain detections are surfaced before anything is read into the numbers`() {
        val shots = gaussianGroup(10, sigma = 12.0, seed = 41).mapIndexed { index, point ->
            Shot(
                id = "s$index",
                position = point,
                orderIndex = index,
                source = ShotSource.AUTO,
                confidence = if (index < 2) 0.3 else 0.95,
            )
        }
        val report = analyseShots(shots, firearm = scopedRifle)

        val tip = assertNotNull(report.tips.find { it.id == "detection.review" })
        assertEquals(TipSeverity.IMPORTANT, tip.severity)
        assertTrue(tip.title.contains("2"))
    }

    @Test
    fun `hand-placed shots are never questioned`() {
        val shots = gaussianGroup(10, sigma = 12.0, seed = 42).mapIndexed { index, point ->
            Shot(id = "s$index", position = point, orderIndex = index, source = ShotSource.MANUAL)
        }
        assertTrue(analyseShots(shots, scopedRifle).tips.none { it.id == "detection.review" })
    }

    @Test
    fun `every report carries the disclaimer`() {
        val report = analyse(gaussianGroup(10, sigma = 12.0, seed = 51), firearm = scopedRifle)
        assertTrue(report.disclaimer.isNotBlank())
    }

    @Test
    fun `tips are ordered by how much they matter`() {
        val report = analyse(
            gaussianGroup(4, sigma = 12.0, seed = 61, centre = PointMm(0.0, 80.0)),
            firearm = scopedRifle,
        )

        val severities = report.tips.map { it.severity.ordinal }
        assertEquals(severities.sortedDescending(), severities, "important tips must come first")
    }

    // --- Equipment ------------------------------------------------------------------------------

    @Test
    fun `spring piston airguns get the hold advice they need`() {
        val springer = Firearm(
            id = "springer",
            name = "Springer",
            type = FirearmType.AIR_RIFLE,
            caliberId = Calibers.AIR_177.id,
            action = ActionType.SPRING_PISTON,
        )
        val report = analyse(
            gaussianGroup(10, sigma = 4.0, seed = 71),
            firearm = springer,
            distanceM = 10.0,
            target = TargetLibrary.ISSF_AIR_RIFLE_10M,
        )

        val tip = assertNotNull(report.tips.find { it.id == "equipment.springer" })
        assertTrue(tip.body.contains("open palm"))
    }

    @Test
    fun `a pre-charged airgun gets no spring piston advice`() {
        val pcp = Firearm(
            id = "pcp",
            name = "PCP",
            type = FirearmType.AIR_RIFLE,
            caliberId = Calibers.AIR_177.id,
            action = ActionType.PRE_CHARGED_PNEUMATIC,
        )
        val report = analyse(gaussianGroup(10, 4.0, 72), firearm = pcp, distanceM = 10.0)
        assertTrue(report.tips.none { it.id == "equipment.springer" })
    }

    // --- Progress -------------------------------------------------------------------------------

    @Test
    fun `a run of improving sessions is recognised`() {
        val history = (0 until 7).map { index ->
            session(
                id = "h$index",
                positions = gaussianGroup(10, sigma = 24.0 - index * 2.2, seed = 100 + index),
                epochMs = index * DAY_MS,
            )
        }
        val latest = session("now", gaussianGroup(10, sigma = 8.0, seed = 200), 7 * DAY_MS)

        val report = CoachingEngine.analyse(CoachingContext(latest, history))
        assertNotNull(report.tips.find { it.id == "progress.improving" })
    }

    @Test
    fun `noise across sessions is not sold as progress`() {
        val history = (0 until 7).map { index ->
            session("h$index", gaussianGroup(10, sigma = 15.0, seed = 300 + index), index * DAY_MS)
        }
        val latest = session("now", gaussianGroup(10, sigma = 14.0, seed = 400), 7 * DAY_MS)

        val report = CoachingEngine.analyse(CoachingContext(latest, history))
        assertNotNull(report.tips.find { it.id == "progress.flat" })
        assertTrue(report.tips.none { it.id == "progress.improving" })
    }

    @Test
    fun `a first ever session gets no progress claim`() {
        val report = analyse(gaussianGroup(10, sigma = 12.0, seed = 500), firearm = scopedRifle)
        assertTrue(report.tips.none { it.category == TipCategory.PROGRESS })
    }

    @Test
    fun `a rule that throws does not take the whole report down`() {
        val exploding = CoachingRule { error("boom") }
        val report = CoachingEngine.analyse(
            CoachingContext(session("s", gaussianGroup(10, 12.0, 600), 0L)),
            rules = listOf(exploding, CoachingRule { emptyList() }),
        )
        assertTrue(report.tips.isEmpty())
    }

    // --- Fixtures ----------------------------------------------------------------------------------

    private val scopedRifle = Firearm(
        id = "rifle",
        name = "Test rifle",
        type = FirearmType.CENTREFIRE_RIFLE,
        caliberId = Calibers.R_308.id,
        action = ActionType.BOLT,
        sight = SightSetup(
            type = SightType.SCOPE,
            clickValue = ClickValue(10.0, ClickUnit.MM_AT_100M),
            opticHeightMm = 45.0,
        ),
        zeroDistanceM = 100.0,
    )

    private val pistol = Firearm(
        id = "pistol",
        name = "Test pistol",
        type = FirearmType.PISTOL,
        caliberId = Calibers.P_9MM.id,
        action = ActionType.SEMI_AUTO,
    )

    private val sloppyAmmo = Ammo(
        id = "bulk",
        brand = "Bulk",
        caliberId = Calibers.R_308.id,
        bulletWeightGrains = 150.0,
        muzzleVelocityMps = Units.fpsToMps(2800.0),
        velocitySdMps = Units.fpsToMps(55.0),
        ballisticCoefficient = 0.398,
    )

    private fun gaussianGroup(
        count: Int,
        sigma: Double,
        seed: Int,
        centre: PointMm = PointMm.ORIGIN,
    ): List<PointMm> {
        val random = Random(seed)
        return List(count) {
            centre + PointMm(
                Statistics.nextGaussian(random) * sigma,
                Statistics.nextGaussian(random) * sigma,
            )
        }
    }

    private fun strungGroup(count: Int, verticalSigma: Double, horizontalSigma: Double): List<PointMm> {
        val random = Random(77)
        return List(count) {
            PointMm(
                Statistics.nextGaussian(random) * horizontalSigma,
                Statistics.nextGaussian(random) * verticalSigma,
            )
        }
    }

    private fun tightGroup(count: Int, at: PointMm) = gaussianGroup(count, sigma = 6.0, seed = 1, centre = at)

    private fun analyse(
        positions: List<PointMm>,
        firearm: Firearm = scopedRifle,
        ammo: Ammo? = null,
        distanceM: Double = 100.0,
        handedness: Handedness = Handedness.UNKNOWN,
        weather: com.nobrainsoft.rangeanalyser.core.model.Weather? = null,
        target: TargetSpec = TargetLibrary.BLANK_A4,
    ): CoachingReport = analyseShots(
        shots = positions.mapIndexed { index, point ->
            Shot(id = "s$index", position = point, orderIndex = index)
        },
        firearm = firearm,
        ammo = ammo,
        distanceM = distanceM,
        handedness = handedness,
        weather = weather,
        target = target,
    )

    private fun analyseShots(
        shots: List<Shot>,
        firearm: Firearm = scopedRifle,
        ammo: Ammo? = null,
        distanceM: Double = 100.0,
        handedness: Handedness = Handedness.UNKNOWN,
        weather: com.nobrainsoft.rangeanalyser.core.model.Weather? = null,
        target: TargetSpec = TargetLibrary.BLANK_A4,
    ): CoachingReport {
        val session = Session(
            id = "session",
            name = "Session",
            firearmId = firearm.id,
            ammoId = ammo?.id,
            targetSpecId = target.id,
            distanceM = distanceM,
            handedness = handedness,
            mode = SessionMode.PHOTO,
            startedAtEpochMs = 0L,
            shots = shots,
            weather = weather,
        )
        val analysed = assertNotNull(AnalysedSession.analyse(session, firearm, ammo, target))
        return CoachingEngine.analyse(CoachingContext(analysed))
    }

    private fun session(id: String, positions: List<PointMm>, epochMs: Long): AnalysedSession {
        val session = Session(
            id = id,
            name = id,
            firearmId = scopedRifle.id,
            targetSpecId = TargetLibrary.BLANK_A4.id,
            distanceM = 100.0,
            mode = SessionMode.PHOTO,
            startedAtEpochMs = epochMs,
            shots = positions.mapIndexed { index, point ->
                Shot(id = "$id-$index", position = point, orderIndex = index)
            },
        )
        return assertNotNull(
            AnalysedSession.analyse(session, scopedRifle, null, TargetLibrary.BLANK_A4),
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
