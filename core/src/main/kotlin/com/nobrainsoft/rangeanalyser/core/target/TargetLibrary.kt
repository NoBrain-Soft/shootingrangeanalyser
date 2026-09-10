package com.nobrainsoft.rangeanalyser.core.target

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.geometry.Units

/**
 * Built-in target faces.
 *
 * Ring diameters are the load-bearing numbers in this whole app - a wrong table silently corrupts
 * every score - so this list contains only faces whose dimensions are published and unambiguous.
 * Targets whose tables could not be sourced with confidence are deliberately absent; the custom
 * target editor covers them, and an empty slot is better than a plausible-looking wrong one.
 */
object TargetLibrary {

    // --- ISSF ----------------------------------------------------------------------------------
    // Ring diameters per the ISSF rulebook, in millimetres, measured to the outside of the ring
    // line. All ISSF faces are evenly spaced, which is what makes decimal scoring meaningful.

    val ISSF_AIR_RIFLE_10M = TargetSpec(
        id = "issf_air_rifle_10m",
        name = "ISSF 10 m Air Rifle",
        discipline = Discipline.PRECISION_AIR,
        blackDiameterMm = 30.5,
        defaultDistanceM = 10.0,
        source = "ISSF Technical Rules - 10 m air rifle face",
        scoring = ScoringModel.Rings(
            rings = ringsFromDiameters(
                10 to 0.5, 9 to 5.5, 8 to 10.5, 7 to 15.5, 6 to 20.5,
                5 to 25.5, 4 to 30.5, 3 to 35.5, 2 to 40.5, 1 to 45.5,
            ),
            supportsDecimal = true,
        ),
    )

    val ISSF_AIR_PISTOL_10M = TargetSpec(
        id = "issf_air_pistol_10m",
        name = "ISSF 10 m Air Pistol",
        discipline = Discipline.PRECISION_AIR,
        blackDiameterMm = 59.5,
        defaultDistanceM = 10.0,
        source = "ISSF Technical Rules - 10 m air pistol face",
        scoring = ScoringModel.Rings(
            rings = ringsFromDiameters(
                10 to 11.5, 9 to 27.5, 8 to 43.5, 7 to 59.5, 6 to 75.5,
                5 to 91.5, 4 to 107.5, 3 to 123.5, 2 to 139.5, 1 to 155.5,
            ),
            supportsDecimal = true,
        ),
    )

    /** Also the ISSF 50 m pistol face - the two share dimensions. */
    val ISSF_PISTOL_25M = TargetSpec(
        id = "issf_pistol_25m",
        name = "ISSF 25 m / 50 m Pistol",
        discipline = Discipline.BULLSEYE_PISTOL,
        blackDiameterMm = 200.0,
        defaultDistanceM = 25.0,
        source = "ISSF Technical Rules - 25 m precision / 50 m pistol face",
        scoring = ScoringModel.Rings(
            rings = ringsFromDiameters(
                10 to 50.0, 9 to 100.0, 8 to 150.0, 7 to 200.0, 6 to 250.0,
                5 to 300.0, 4 to 350.0, 3 to 400.0, 2 to 450.0, 1 to 500.0,
            ),
            supportsDecimal = true,
        ),
    )

    val ISSF_RAPID_FIRE_25M = TargetSpec(
        id = "issf_rapid_fire_25m",
        name = "ISSF 25 m Rapid Fire Pistol",
        discipline = Discipline.BULLSEYE_PISTOL,
        blackDiameterMm = 500.0,
        defaultDistanceM = 25.0,
        source = "ISSF Technical Rules - 25 m rapid fire face",
        scoring = ScoringModel.Rings(
            rings = ringsFromDiameters(
                10 to 100.0, 9 to 180.0, 8 to 260.0, 7 to 340.0, 6 to 420.0, 5 to 500.0,
            ),
            supportsDecimal = true,
        ),
    )

    val ISSF_RIFLE_50M = TargetSpec(
        id = "issf_rifle_50m",
        name = "ISSF 50 m Rifle",
        discipline = Discipline.PRECISION_SMALLBORE,
        // Unlike the other faces, this aiming mark does not coincide with a scoring ring: it sits
        // between the 4 ring (106.4 mm) and the 3 ring (122.4 mm). Pinned by a test so it is not
        // "corrected" later.
        blackDiameterMm = 112.4,
        defaultDistanceM = 50.0,
        source = "ISSF Technical Rules - 50 m rifle face; black does not align to a ring",
        scoring = ScoringModel.Rings(
            rings = ringsFromDiameters(
                10 to 10.4, 9 to 26.4, 8 to 42.4, 7 to 58.4, 6 to 74.4,
                5 to 90.4, 4 to 106.4, 3 to 122.4, 2 to 138.4, 1 to 154.4,
            ),
            supportsDecimal = true,
        ),
    )

    val ISSF_RIFLE_300M = TargetSpec(
        id = "issf_rifle_300m",
        name = "ISSF 300 m Rifle",
        discipline = Discipline.PRECISION_FULLBORE,
        blackDiameterMm = 600.0,
        defaultDistanceM = 300.0,
        source = "ISSF Technical Rules - 300 m rifle face",
        scoring = ScoringModel.Rings(
            rings = ringsFromDiameters(
                10 to 100.0, 9 to 200.0, 8 to 300.0, 7 to 400.0, 6 to 500.0,
                5 to 600.0, 4 to 700.0, 3 to 800.0, 2 to 900.0, 1 to 1000.0,
            ),
            supportsDecimal = true,
        ),
    )

    // --- NRA -----------------------------------------------------------------------------------

    /**
     * The B-8 is published in inches and its rings are **not** evenly spaced, which is exactly why
     * the scorer looks step sizes up per ring instead of assuming a constant.
     */
    val NRA_B8 = TargetSpec(
        id = "nra_b8",
        name = "NRA B-8 (25 yd)",
        discipline = Discipline.BULLSEYE_PISTOL,
        blackDiameterMm = Units.inchToMm(5.54),
        defaultDistanceM = Units.yardsToMetres(25.0),
        source = "NRA B-8 sustained fire pistol target",
        scoring = ScoringModel.Rings(
            rings = listOf(
                Ring(10, Units.inchToMm(3.36)),
                Ring(9, Units.inchToMm(5.54)),
                Ring(8, Units.inchToMm(8.00)),
                Ring(7, Units.inchToMm(11.00)),
                Ring(6, Units.inchToMm(14.80)),
                Ring(5, Units.inchToMm(19.68)),
            ),
            innerRingDiameterMm = Units.inchToMm(1.695),
            innerRingLabel = "X",
            // Unevenly spaced rings make tenth-of-a-ring scoring meaningless here, and the NRA
            // scores it as integers with an X count anyway.
            supportsDecimal = false,
        ),
    )

    // --- Practical -----------------------------------------------------------------------------
    // The scoring zone that decides most hits - the IPSC A zone and the IDPA -0 - is dimensioned
    // from the rules. The surrounding silhouette outline is approximated, so these are marked
    // unverified and the UI says so. Editing the outline in the target editor makes them exact.

    val IPSC_CLASSIC = TargetSpec(
        id = "ipsc_classic",
        name = "IPSC Classic",
        discipline = Discipline.PRACTICAL,
        sheetWidthMm = 450.0,
        sheetHeightMm = 750.0,
        dimensionsVerified = false,
        source = "A zone 150 x 280 mm and 450 mm width per IPSC rules; outer zone outline approximate",
        scoring = ScoringModel.Zones(
            listOf(
                Zone("A", 5, ZoneShape.Rect(PointMm.ORIGIN, 150.0, 280.0)),
                Zone("A-head", 5, ZoneShape.Rect(PointMm(0.0, 320.0), 100.0, 150.0)),
                Zone("C", 3, ZoneShape.Rect(PointMm(0.0, 20.0), 450.0, 420.0)),
                Zone("C-head", 3, ZoneShape.Rect(PointMm(0.0, 320.0), 150.0, 190.0)),
                Zone("D", 1, ZoneShape.Rect(PointMm(0.0, -70.0), 450.0, 585.0)),
            ),
        ),
    )

    val IDPA = TargetSpec(
        id = "idpa",
        name = "IDPA",
        discipline = Discipline.PRACTICAL,
        sheetWidthMm = Units.inchToMm(18.0),
        sheetHeightMm = Units.inchToMm(30.0),
        dimensionsVerified = false,
        source = "-0 chest circle 8 in and head box 4 x 6 in per IDPA rules; outer zone outline approximate",
        scoring = ScoringModel.Zones(
            listOf(
                // Higher points means "better hit" internally; the UI renders IDPA's
                // down-points the way shooters expect.
                Zone("-0", 5, ZoneShape.Circle(PointMm.ORIGIN, Units.inchToMm(8.0))),
                Zone(
                    "-0 head",
                    5,
                    ZoneShape.Rect(
                        PointMm(0.0, Units.inchToMm(10.0)),
                        Units.inchToMm(4.0),
                        Units.inchToMm(6.0),
                    ),
                ),
                Zone("-1", 3, ZoneShape.Rect(PointMm.ORIGIN, Units.inchToMm(11.0), Units.inchToMm(17.0))),
                Zone("-3", 1, ZoneShape.Rect(PointMm.ORIGIN, Units.inchToMm(18.0), Units.inchToMm(24.0))),
            ),
        ),
    )

    // --- Unscored faces --------------------------------------------------------------------------
    // For load development and plinking, where only group geometry matters. These still carry a
    // sheet size or a grid so the calibrator has a known-size reference to work from.

    val BLANK_A4 = TargetSpec(
        id = "blank_a4",
        name = "Blank A4 sheet",
        discipline = Discipline.GENERAL,
        scoring = ScoringModel.NoScoring,
        sheetWidthMm = 210.0,
        sheetHeightMm = 297.0,
        source = "ISO A4",
    )

    val BLANK_LETTER = TargetSpec(
        id = "blank_letter",
        name = "Blank US Letter sheet",
        discipline = Discipline.GENERAL,
        scoring = ScoringModel.NoScoring,
        sheetWidthMm = Units.inchToMm(8.5),
        sheetHeightMm = Units.inchToMm(11.0),
        source = "US Letter",
    )

    val GRID_1CM_A4 = TargetSpec(
        id = "grid_1cm_a4",
        name = "1 cm grid (A4)",
        discipline = Discipline.LOAD_DEVELOPMENT,
        scoring = ScoringModel.NoScoring,
        sheetWidthMm = 210.0,
        sheetHeightMm = 297.0,
        gridSpacingMm = 10.0,
        source = "ISO A4 with 10 mm grid",
    )

    val GRID_1IN_LETTER = TargetSpec(
        id = "grid_1in_letter",
        name = "1 inch grid (Letter)",
        discipline = Discipline.LOAD_DEVELOPMENT,
        scoring = ScoringModel.NoScoring,
        sheetWidthMm = Units.inchToMm(8.5),
        sheetHeightMm = Units.inchToMm(11.0),
        gridSpacingMm = Units.inchToMm(1.0),
        source = "US Letter with 1 in grid",
    )

    /** An aiming dot of known size doubles as a calibration reference. */
    val DOT_1IN_A4 = TargetSpec(
        id = "dot_1in_a4",
        name = "1 inch aiming dot (A4)",
        discipline = Discipline.LOAD_DEVELOPMENT,
        scoring = ScoringModel.NoScoring,
        sheetWidthMm = 210.0,
        sheetHeightMm = 297.0,
        blackDiameterMm = Units.inchToMm(1.0),
        source = "1 in aiming dot on A4",
    )

    val all: List<TargetSpec> = listOf(
        ISSF_AIR_RIFLE_10M,
        ISSF_AIR_PISTOL_10M,
        ISSF_PISTOL_25M,
        ISSF_RAPID_FIRE_25M,
        ISSF_RIFLE_50M,
        ISSF_RIFLE_300M,
        NRA_B8,
        IPSC_CLASSIC,
        IDPA,
        BLANK_A4,
        BLANK_LETTER,
        GRID_1CM_A4,
        GRID_1IN_LETTER,
        DOT_1IN_A4,
    )

    private val byId: Map<String, TargetSpec> = all.associateBy { it.id }

    fun find(id: String): TargetSpec? = byId[id]

    fun of(discipline: Discipline): List<TargetSpec> = all.filter { it.discipline == discipline }

    private fun ringsFromDiameters(vararg pairs: Pair<Int, Double>): List<Ring> =
        pairs.map { (value, diameter) -> Ring(value, diameter) }
}
