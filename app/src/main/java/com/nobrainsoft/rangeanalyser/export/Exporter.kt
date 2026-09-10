package com.nobrainsoft.rangeanalyser.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.scoring
import com.nobrainsoft.rangeanalyser.core.scoring.Scorer
import com.nobrainsoft.rangeanalyser.data.Mappers
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale

/**
 * Gets data out of the app.
 *
 * Range data has a habit of outliving the app that recorded it, so both formats here are plain and
 * self-describing: CSV that opens in any spreadsheet, and JSON that round-trips back in. Nothing is
 * locked in.
 */
class Exporter(private val context: Context, private val exportsDirectory: File) {

    /** One row per shot, with everything needed to redo the analysis elsewhere. */
    fun toCsv(analysed: AnalysedSession): File {
        val session = analysed.session
        val scorer = analysed.target?.let { Scorer(it, analysed.firearm?.caliber()) }
        val distance = session.distanceM

        val file = File(exportsDirectory, "${safeName(session.name)}-shots.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine(
                listOf(
                    "shot", "x_mm", "y_mm", "radius_mm", "radius_moa",
                    "ring", "decimal", "zone", "confidence", "source", "excluded", "time_ms",
                ).joinToString(","),
            )

            session.shots.sortedBy { it.orderIndex }.forEachIndexed { index, shot ->
                val score = scorer?.score(shot.position)
                writer.appendLine(
                    listOf(
                        (index + 1).toString(),
                        format(shot.position.x),
                        format(shot.position.y),
                        format(shot.position.radius),
                        format(Units.mmToMoa(shot.position.radius, distance)),
                        score?.ringValue?.toString().orEmpty(),
                        score?.decimalScore?.let { format(it) }.orEmpty(),
                        score?.zoneLabel.orEmpty(),
                        format(shot.confidence),
                        shot.source.name,
                        shot.excluded.toString(),
                        shot.timestampMs?.toString().orEmpty(),
                    ).joinToString(","),
                )
            }
        }
        return file
    }

    /** The session exactly as stored, so it can be imported back without loss. */
    fun toJson(session: Session): File {
        val file = File(exportsDirectory, "${safeName(session.name)}.json")
        file.writeText(Mappers.json.encodeToString(session))
        return file
    }

    fun importJson(file: File): Session = Mappers.json.decodeFromString(file.readText())

    /**
     * A summary a person can read, for pasting into a range log or a forum post.
     */
    fun toSummaryText(analysed: AnalysedSession): String {
        val stats = analysed.stats
        return buildString {
            appendLine(analysed.session.name)
            analysed.firearm?.let { appendLine("Rifle/pistol: ${it.name}") }
            analysed.ammo?.let { appendLine("Load: ${it.displayName}") }
            appendLine("Distance: ${format(analysed.session.distanceM)} m")
            appendLine("Shots: ${stats.shotCount}")
            appendLine(
                "Group: ${format(stats.extremeSpreadMm)} mm " +
                    "(${format(stats.extremeSpreadMoa())} MOA)",
            )
            appendLine(
                "Mean radius: ${format(stats.meanRadiusMm)} mm " +
                    "(${format(stats.meanRadiusMoa())} MOA)",
            )
            stats.extremeSpreadCi?.let {
                appendLine(
                    "A group of this size would plausibly measure " +
                        "${format(it.lower)}-${format(it.upper)} mm on a repeat.",
                )
            }
            appendLine("Centre of impact: ${format(stats.centroidOffset.radius)} mm from aim point")
        }
    }

    /** Hands a file to whatever the user picks, without granting the app any broader access. */
    fun shareIntent(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "session" }
}
