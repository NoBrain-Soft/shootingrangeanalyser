package com.nobrainsoft.rangeanalyser.ui.live

import android.content.Context
import android.speech.tts.TextToSpeech
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.scoring.ShotScore
import com.nobrainsoft.rangeanalyser.data.SpeechVerbosity
import java.util.Locale

/**
 * Calls shots aloud, the way a spotter would.
 *
 * Every announcement is also returned as text so the screen can show it. On a range the shooter is
 * wearing ear protection and may hear none of this, so nothing the app says is ever *only* spoken -
 * the spoken call is a convenience layered over the visible one, not a channel of its own.
 */
class SpeechAnnouncer(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            // setLanguage returns an int status, so Kotlin exposes no `language` setter.
            if (ready) engine?.setLanguage(Locale.getDefault())
        }
    }

    /**
     * Builds the call for a shot and speaks it if speech is on.
     *
     * Returns the text either way, because the caller always displays it.
     */
    fun announce(
        shotNumber: Int,
        score: ShotScore?,
        position: PointMm,
        verbosity: SpeechVerbosity,
    ): String {
        val text = describe(shotNumber, score, position, verbosity)
        if (verbosity != SpeechVerbosity.OFF && ready && text.isNotBlank()) {
            engine?.speak(text, TextToSpeech.QUEUE_ADD, null, "shot-$shotNumber")
        }
        return text
    }

    fun describe(
        shotNumber: Int,
        score: ShotScore?,
        position: PointMm,
        verbosity: SpeechVerbosity,
    ): String = when (verbosity) {
        SpeechVerbosity.OFF -> shortForm(score)
        SpeechVerbosity.SCORE_ONLY -> shortForm(score)
        SpeechVerbosity.FULL -> buildString {
            append("Shot $shotNumber")
            shortForm(score).takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
            position.clockPosition()?.let { append(", $it o'clock") }
        }
    }

    private fun shortForm(score: ShotScore?): String = when {
        score == null -> ""
        score.isMiss -> "miss"
        score.decimalScore != null -> String.format(Locale.ROOT, "%.1f", score.decimalScore)
        score.zoneLabel != null -> score.zoneLabel!!
        score.ringValue != null -> score.ringValue.toString()
        else -> ""
    }

    fun stop() {
        engine?.stop()
    }

    fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}
