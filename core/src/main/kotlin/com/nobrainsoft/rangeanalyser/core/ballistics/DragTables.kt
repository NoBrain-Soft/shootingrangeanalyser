package com.nobrainsoft.rangeanalyser.core.ballistics

/**
 * Drag coefficient as a function of Mach number for a standard reference projectile.
 *
 * Values are linearly interpolated; between tabulated points the real curve is smooth enough that
 * the error is far below the accuracy this module claims.
 */
class DragTable(private val machNumbers: DoubleArray, private val dragCoefficients: DoubleArray) {
    init {
        require(machNumbers.size == dragCoefficients.size) { "drag table is ragged" }
        require(machNumbers.size >= 2) { "drag table needs at least two points" }
    }

    fun dragCoefficientAt(mach: Double): Double {
        if (mach <= machNumbers.first()) return dragCoefficients.first()
        if (mach >= machNumbers.last()) return dragCoefficients.last()

        var high = machNumbers.indexOfFirst { it >= mach }
        if (high <= 0) high = 1
        val low = high - 1

        val span = machNumbers[high] - machNumbers[low]
        if (span <= 0.0) return dragCoefficients[low]

        val weight = (mach - machNumbers[low]) / span
        return dragCoefficients[low] * (1 - weight) + dragCoefficients[high] * weight
    }
}

/**
 * The two standard drag functions that cover practically all small-arms ammunition.
 *
 * G1 is the blunt flat-based reference shape almost every factory box quotes against. G7 matches
 * modern boat-tail match bullets far better, and where a manufacturer publishes one it is the
 * better choice - a G1 number for a long ogive bullet varies with velocity in a way the model
 * cannot capture.
 *
 * Accuracy note: on a .308 168 gr match load these tables reproduce published downrange velocity to
 * within about 1% and time of flight to within a few milliseconds, which is ample for this module's
 * only real job - estimating how much vertical dispersion a load's velocity spread accounts for.
 * That estimate depends chiefly on time of flight, not on fine drag detail. It is still not a
 * substitute for a ballistic calculator, and nothing here should be used to dial a scope.
 */
object DragTables {

    val G1 = DragTable(
        machNumbers = doubleArrayOf(
            0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45,
            0.50, 0.55, 0.60, 0.70, 0.725, 0.75, 0.775, 0.80, 0.825, 0.85,
            0.875, 0.90, 0.925, 0.95, 0.975, 1.00, 1.025, 1.05, 1.075, 1.10,
            1.125, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.50, 1.60, 1.80,
            2.00, 2.20, 2.40, 2.60, 3.00, 3.60, 4.00, 5.00,
        ),
        dragCoefficients = doubleArrayOf(
            0.2629, 0.2558, 0.2487, 0.2413, 0.2344, 0.2278, 0.2214, 0.2155, 0.2104, 0.2061,
            0.2032, 0.2020, 0.2034, 0.2165, 0.2230, 0.2313, 0.2417, 0.2546, 0.2706, 0.2901,
            0.3136, 0.3415, 0.3734, 0.4084, 0.4448, 0.4805, 0.5136, 0.5427, 0.5677, 0.5883,
            0.6053, 0.6191, 0.6393, 0.6518, 0.6589, 0.6621, 0.6625, 0.6573, 0.6464, 0.6199,
            0.5939, 0.5704, 0.5484, 0.5285, 0.4942, 0.4555, 0.4366, 0.4015,
        ),
    )

    val G7 = DragTable(
        machNumbers = doubleArrayOf(
            0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45,
            0.50, 0.55, 0.60, 0.65, 0.70, 0.725, 0.75, 0.775, 0.80, 0.825,
            0.85, 0.875, 0.90, 0.925, 0.95, 0.975, 1.00, 1.025, 1.05, 1.075,
            1.10, 1.125, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.50, 1.60,
            1.80, 2.00, 2.20, 2.40, 2.60, 3.00, 3.60, 4.00, 5.00,
        ),
        dragCoefficients = doubleArrayOf(
            0.1198, 0.1197, 0.1196, 0.1194, 0.1193, 0.1194, 0.1194, 0.1194, 0.1193, 0.1193,
            0.1194, 0.1193, 0.1194, 0.1197, 0.1202, 0.1207, 0.1215, 0.1226, 0.1242, 0.1266,
            0.1306, 0.1368, 0.1464, 0.1660, 0.2054, 0.2993, 0.3803, 0.4015, 0.4043, 0.4034,
            0.4014, 0.3987, 0.3955, 0.3884, 0.3810, 0.3732, 0.3657, 0.3580, 0.3440, 0.3315,
            0.3097, 0.2914, 0.2752, 0.2607, 0.2477, 0.2254, 0.1980, 0.1834, 0.1600,
        ),
    )
}
