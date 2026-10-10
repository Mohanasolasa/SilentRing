package com.example.autosilent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.scheduler.DiagnosticLogger
import com.example.autosilent.ui.navigation.AppNavigation
import com.example.autosilent.ui.theme.AutoSilentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AutoSilentApplication
        DiagnosticLogger.i(
            "AutoSilentDiag",
            "MAIN_ACTIVITY_LAUNCHED: exactAlarm=${app.alarmScheduler.canScheduleExactAlarms()}, soundControl=${app.soundController.canControlSound()}"
        )
        setContent {
            AutoSilentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(app = app)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLogger.d("AutoSilentDiag", "MAIN_ACTIVITY_ON_RESUME")
    }

    override fun onPause() {
        super.onPause()
        DiagnosticLogger.d("AutoSilentDiag", "MAIN_ACTIVITY_ON_PAUSE")
    }
}
