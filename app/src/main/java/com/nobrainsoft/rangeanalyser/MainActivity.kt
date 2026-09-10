package com.nobrainsoft.rangeanalyser

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.data.AppSettings
import com.nobrainsoft.rangeanalyser.ui.RangeNavGraph
import com.nobrainsoft.rangeanalyser.ui.theme.RangeAnalyserTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings by appContainer.settings.settings
                .collectAsStateWithLifecycle(AppSettings())

            // A phone that sleeps mid-string is useless, and reaching over to wake it is worse
            // than useless on a firing point.
            LaunchedEffect(settings.keepScreenOnWhileWatching) {
                if (settings.keepScreenOnWhileWatching) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            RangeAnalyserTheme(theme = settings.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RangeNavGraph()
                }
            }
        }
    }
}
