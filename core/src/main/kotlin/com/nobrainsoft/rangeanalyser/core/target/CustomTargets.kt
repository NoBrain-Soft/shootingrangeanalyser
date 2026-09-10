package com.nobrainsoft.rangeanalyser.core.target

/**
 * Builds a target face from what the shooter measured.
 *
 * Most ranges have a face that is nobody's published standard - a club's own print, an importer's
 * variant, something photocopied onto A3. Without this the app can only offer targets from its own
 * library, which is useless to the person standing in front of a different one.
 *
 * The validation here is the substance. A ring table that is out of order, has duplicates, or is
 * larger than the paper it is printed on will produce plausible-looking scores that are wrong, and
 * a wrong score is worse than a refused one - so every failure is a message the shooter can act on
 * rather than a silently repaired table.
 */
object CustomTargets {

    /** Paper the shooter is likely to actually have. */
    data class Sheet(val label: String, val widthMm: Double, val heightMm: Double) {
        companion object {
            val A4 = Sheet("A4", 210.0, 297.0)
            val A3 = Sheet("A3", 297.0, 420.0)
            val A5 = Sheet("A5", 148.0, 210.0)
            val LETTER = Sheet("Letter", 215.9, 279.4)
            val TABLOID = Sheet("Tabloid", 279.4, 431.8)

            val common = listOf(A4, A3, A5, LETTER, TABLOID)
        }
    }

    /** What the editor collects, before it is known to be a valid face. */
    data class Draft(
        val name: String = "",
        val discipline: Discipline = Discipline.GENERAL,
        val sheetWidthMm: Double? = null,
        val sheetHeightMm: Double? = null,
        /** Outermost first or innermost first - either is accepted and sorted out here. */
        val ringDiametersMm: List<Double> = emptyList(),
        /** Value of the smallest ring. Ten for most faces, five for some practical ones. */
        val highestRingValue: Int = 10,
        val blackDiameterMm: Double? = null,
        val innerRingDiameterMm: Double? = null,
        val defaultDistanceM: Double? = null,
    )

    /** Why a draft cannot become a target, in words the shooter can act on. */
    sealed interface Problem {
        val message: String

        data object NoName : Problem {
            override val message = "Give the target a name so you can find it again."
        }

        data object NoRings : Problem {
            override val message = "Measure at least one ring."
        }

        data class NotPositive(val diameterMm: Double) : Problem {
            override val message = "A ring cannot be ${format(diameterMm)} mm across."
        }

        data class Duplicate(val diameterMm: Double) : Problem {
            override val message =
                "Two rings are both ${format(diameterMm)} mm across. Measure them again."
        }

        data class LargerThanSheet(val diameterMm: Double, val sheetMm: Double) : Problem {
            override val message =
                "The outer ring measures ${format(diameterMm)} mm, which will not fit on paper " +
                    "${format(sheetMm)} mm across. Check the scale you set."
        }

        data class InnerRingTooLarge(val innerMm: Double, val smallestRingMm: Double) : Problem {
            override val message =
                "The inner tie-break circle (${format(innerMm)} mm) has to be smaller than the " +
                    "highest ring (${format(smallestRingMm)} mm)."
        }

        data class BlackTooLarge(val blackMm: Double, val sheetMm: Double) : Problem {
            override val message =
                "The aiming mark (${format(blackMm)} mm) is wider than the paper."
        }
    }

    /**
     * Turns a draft into a face, or explains why not.
     *
     * Note that ring *values* are assigned from the measurements: the smallest ring gets
     * [Draft.highestRingValue] and each larger ring counts down. That is how every ring target is
     * numbered, and inferring it saves the shooter typing a value next to each measurement.
     */
    fun build(id: String, draft: Draft): Result<TargetSpec> {
        problemWith(draft)?.let { return Result.failure(IllegalArgumentException(it.message)) }

        val ascending = draft.ringDiametersMm.sorted()
        val rings = ascending.mapIndexed { index, diameter ->
            Ring(value = draft.highestRingValue - index, diameterMm = diameter)
        }

        val scoring = ScoringModel.Rings(
            rings = rings,
            innerRingDiameterMm = draft.innerRingDiameterMm,
            innerRingLabel = draft.innerRingDiameterMm?.let { "X" },
            // Decimal scoring divides one ring's width into ten, which only means anything when
            // every ring is the same width. Offering it on an uneven face would invent precision.
            supportsDecimal = ScoringModel.Rings(rings).hasUniformStep && rings.size > 1,
        )

        return Result.success(
            TargetSpec(
                id = id,
                name = draft.name.trim(),
                discipline = draft.discipline,
                scoring = scoring,
                sheetWidthMm = draft.sheetWidthMm,
                sheetHeightMm = draft.sheetHeightMm,
                blackDiameterMm = draft.blackDiameterMm,
                defaultDistanceM = draft.defaultDistanceM,
                isBuiltIn = false,
                // Measured by the shooter off their own target, so it is exactly as good as their
                // measuring was. Saying it is verified would be someone else's claim, not ours.
                dimensionsVerified = false,
                source = "Measured on this phone",
            ),
        )
    }

    /** The first thing wrong with [draft], or null when it will build. */
    fun problemWith(draft: Draft): Problem? {
        if (draft.name.isBlank()) return Problem.NoName
        if (draft.ringDiametersMm.isEmpty()) return Problem.NoRings

        draft.ringDiametersMm.firstOrNull { it <= 0.0 }?.let { return Problem.NotPositive(it) }

        // Rounded before comparing: two taps a fifth of a millimetre apart are the same ring
        // measured twice, and the ring table forbids duplicates outright.
        val rounded = draft.ringDiametersMm.map { Math.round(it * 10.0) }
        rounded.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
            return Problem.Duplicate(it.first() / 10.0)
        }

        val widest = draft.ringDiametersMm.max()
        val sheet = listOfNotNull(draft.sheetWidthMm, draft.sheetHeightMm).maxOrNull()
        if (sheet != null && widest > sheet * SHEET_TOLERANCE) {
            return Problem.LargerThanSheet(widest, sheet)
        }

        draft.innerRingDiameterMm?.let { inner ->
            val smallest = draft.ringDiametersMm.min()
            if (inner >= smallest) return Problem.InnerRingTooLarge(inner, smallest)
            if (inner <= 0.0) return Problem.NotPositive(inner)
        }

        draft.blackDiameterMm?.let { black ->
            if (black <= 0.0) return Problem.NotPositive(black)
            if (sheet != null && black > sheet * SHEET_TOLERANCE) {
                return Problem.BlackTooLarge(black, sheet)
            }
        }

        return null
    }

    /**
     * Evenly spaced rings, for the common case of a face whose outer ring and ring count are known.
     *
     * Returns diameters from smallest to largest.
     */
    fun evenlySpaced(outerDiameterMm: Double, ringCount: Int): List<Double> {
        if (outerDiameterMm <= 0.0 || ringCount < 1) return emptyList()
        val step = outerDiameterMm / ringCount
        return (1..ringCount).map { it * step }
    }

    /**
     * Diameter of a ring from a tap on its edge, given the target centre and the measured scale.
     *
     * Both points are in image pixels; the result is millimetres.
     */
    fun diameterFromTap(
        centreX: Double,
        centreY: Double,
        edgeX: Double,
        edgeY: Double,
        millimetresPerPixel: Double,
    ): Double {
        val dx = edgeX - centreX
        val dy = edgeY - centreY
        return 2.0 * kotlin.math.sqrt(dx * dx + dy * dy) * millimetresPerPixel
    }

    /** Millimetres per pixel from two tapped points a known real distance apart. */
    fun scaleFrom(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        realDistanceMm: Double,
    ): Double? {
        val dx = toX - fromX
        val dy = toY - fromY
        val pixels = kotlin.math.sqrt(dx * dx + dy * dy)
        if (pixels < MIN_REFERENCE_PIXELS || realDistanceMm <= 0.0) return null
        return realDistanceMm / pixels
    }

    private fun format(millimetres: Double): String =
        if (millimetres % 1.0 == 0.0) {
            millimetres.toInt().toString()
        } else {
            String.format("%.1f", millimetres)
        }

    /** A little slack, because a face is often printed right out to the edge of the paper. */
    private const val SHEET_TOLERANCE = 1.02

    /** Two taps closer than this are a slip, not a measurement. */
    private const val MIN_REFERENCE_PIXELS = 20.0
}
