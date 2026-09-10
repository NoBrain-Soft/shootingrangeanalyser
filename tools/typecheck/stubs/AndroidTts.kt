@file:Suppress("unused", "UNUSED_PARAMETER")

package android.speech.tts

import android.content.Context
import android.content.stub
import java.util.Locale

class TextToSpeech(context: Context, listener: OnInitListener) {
    fun getLanguage(): Locale = stub()
    fun setLanguage(locale: Locale): Int = stub()
    fun speak(text: CharSequence, queueMode: Int, params: android.os.Bundle?, utteranceId: String?): Int = stub()
    fun stop(): Int = stub()
    fun shutdown(): Unit = stub()

    fun interface OnInitListener {
        fun onInit(status: Int)
    }

    companion object {
        const val SUCCESS: Int = 0
        const val ERROR: Int = -1
        const val QUEUE_FLUSH: Int = 0
        const val QUEUE_ADD: Int = 1
        const val LANG_MISSING_DATA: Int = -1
        const val LANG_NOT_SUPPORTED: Int = -2
    }
}
