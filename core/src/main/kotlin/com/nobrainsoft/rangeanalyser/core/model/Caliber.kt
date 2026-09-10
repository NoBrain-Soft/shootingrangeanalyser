package com.nobrainsoft.rangeanalyser.core.model

import kotlinx.serialization.Serializable

/**
 * A projectile diameter.
 *
 * This drives two things that matter a lot: how big a hole the detector should expect (in pixels,
 * once combined with the calibration), and edge-gauged scoring - a shot counts for the higher ring
 * if the *hole edge* touches the line, so a .45 earns more from the same centre position than a
 * .22 does.
 */
@Serializable
data class Caliber(
    val id: String,
    val displayName: String,
    val bulletDiameterMm: Double,
    val kind: CaliberKind,
) {
    val bulletRadiusMm: Double get() = bulletDiameterMm / 2.0
}

enum class CaliberKind {
    AIRGUN,
    RIMFIRE,
    PISTOL,
    RIFLE,
    SHOTGUN,
}

/**
 * Built-in calibre catalogue. Diameters are bullet (not case or bore) diameters in millimetres.
 *
 * Users can define their own; this is only the convenient starting set.
 */
object Calibers {
    val AIR_177 = Caliber("air_177", ".177 / 4.5 mm", 4.50, CaliberKind.AIRGUN)
    val AIR_20 = Caliber("air_20", ".20 / 5.0 mm", 5.00, CaliberKind.AIRGUN)
    val AIR_22 = Caliber("air_22", ".22 / 5.5 mm", 5.50, CaliberKind.AIRGUN)
    val AIR_25 = Caliber("air_25", ".25 / 6.35 mm", 6.35, CaliberKind.AIRGUN)

    val RF_17_HMR = Caliber("rf_17hmr", ".17 HMR", 4.37, CaliberKind.RIMFIRE)
    val RF_22_LR = Caliber("rf_22lr", ".22 LR", 5.69, CaliberKind.RIMFIRE)
    val RF_22_WMR = Caliber("rf_22wmr", ".22 WMR", 5.69, CaliberKind.RIMFIRE)

    val R_223 = Caliber("r_223", ".223 / 5.56 mm", 5.70, CaliberKind.RIFLE)
    val R_243 = Caliber("r_243", ".243 / 6 mm", 6.17, CaliberKind.RIFLE)
    val R_65 = Caliber("r_65", "6.5 mm", 6.71, CaliberKind.RIFLE)
    val R_270 = Caliber("r_270", ".270", 7.04, CaliberKind.RIFLE)
    val R_7MM = Caliber("r_7mm", "7 mm", 7.21, CaliberKind.RIFLE)
    val R_308 = Caliber("r_308", ".308 / 7.62 mm", 7.82, CaliberKind.RIFLE)
    val R_3006 = Caliber("r_3006", ".30-06", 7.82, CaliberKind.RIFLE)
    val R_8MM = Caliber("r_8mm", "8 mm", 8.22, CaliberKind.RIFLE)
    val R_338 = Caliber("r_338", ".338", 8.59, CaliberKind.RIFLE)
    val R_50BMG = Caliber("r_50bmg", ".50 BMG", 12.95, CaliberKind.RIFLE)

    val P_9MM = Caliber("p_9mm", "9 mm Luger", 9.01, CaliberKind.PISTOL)
    val P_38 = Caliber("p_38", ".38 / .357", 9.07, CaliberKind.PISTOL)
    val P_40 = Caliber("p_40", ".40 S&W", 10.17, CaliberKind.PISTOL)
    val P_10MM = Caliber("p_10mm", "10 mm Auto", 10.17, CaliberKind.PISTOL)
    val P_44 = Caliber("p_44", ".44", 10.90, CaliberKind.PISTOL)
    val P_45 = Caliber("p_45", ".45 ACP", 11.48, CaliberKind.PISTOL)

    val SG_12_SLUG = Caliber("sg_12slug", "12 ga slug", 18.53, CaliberKind.SHOTGUN)

    val all: List<Caliber> = listOf(
        AIR_177, AIR_20, AIR_22, AIR_25,
        RF_17_HMR, RF_22_LR, RF_22_WMR,
        R_223, R_243, R_65, R_270, R_7MM, R_308, R_3006, R_8MM, R_338, R_50BMG,
        P_9MM, P_38, P_40, P_10MM, P_44, P_45,
        SG_12_SLUG,
    )

    private val byId: Map<String, Caliber> = all.associateBy { it.id }

    fun find(id: String): Caliber? = byId[id]

    fun of(kind: CaliberKind): List<Caliber> = all.filter { it.kind == kind }
}
