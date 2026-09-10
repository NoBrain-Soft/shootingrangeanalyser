package com.nobrainsoft.rangeanalyser.core.coach

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One piece of advice, with the numbers that produced it.
 *
 * [evidence] is not decoration. Shooting is full of confident folklore, and a tip that shows its
 * working can be checked, argued with, and ignored when the shooter knows something the app does
 * not. A tip that just asserts cannot.
 */
@Serializable
data class Tip(
    val id: String,
    val title: String,
    val body: String,
    val category: TipCategory,
    val severity: TipSeverity,
    val confidence: TipConfidence,
    val evidence: List<Evidence> = emptyList(),
)

@Serializable
data class Evidence(val label: String, val value: String)

enum class TipCategory {
    /** Where the group sits: a sight problem, not a shooting problem. */
    ZERO,
    TECHNIQUE,
    AMMUNITION,
    EQUIPMENT,

    /** What the data can and cannot support. */
    STATISTICS,
    PROGRESS,

    /** Something about the analysis itself needs the user's attention. */
    DETECTION,
}

enum class TipSeverity {
    /** Worth knowing. */
    INFO,

    /** Worth trying. */
    SUGGESTION,

    /** Read this one. */
    IMPORTANT,
}

enum class TipConfidence {
    /** Follows directly from the measurements. */
    HIGH,

    /** A well-supported inference, but other explanations exist. */
    MEDIUM,

    /** A common association in shooting lore rather than a measured fact. */
    LOW,
}

@Serializable
data class CoachingReport(
    val tips: List<Tip>,
    val disclaimer: String = DEFAULT_DISCLAIMER,
) {
    fun of(category: TipCategory): List<Tip> = tips.filter { it.category == category }

    val important: List<Tip> get() = tips.filter { it.severity == TipSeverity.IMPORTANT }

    companion object {
        const val DEFAULT_DISCLAIMER: String =
            "These notes come from the numbers in this session. Several are common associations " +
                "rather than proven cause and effect, and each one shows the figures behind it so " +
                "you can judge for yourself."
    }
}

/** Number formatting for tip text. Locale-independent so the wording is stable. */
internal object Format {
    fun mm(value: Double): String = "${round(value, 1)} mm"

    fun mmPerShot(value: Double): String = "${round(value, 2)} mm/shot"

    fun mmPerSession(value: Double): String = "${round(value, 1)} mm per session"

    fun ratio(value: Double): String = "${round(value, 1)}:1"

    fun percent(fraction: Double): String = "${round(fraction * 100, 0)}%"

    fun round(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    /** Turns a clock hour into the phrase a spotter would use. */
    fun clock(hour: Int?): String = if (hour == null) "the centre" else "$hour o'clock"
}
